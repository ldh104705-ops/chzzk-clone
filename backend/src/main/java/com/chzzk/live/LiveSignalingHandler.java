package com.chzzk.live;

import com.chzzk.broadcast.Broadcast;
import com.chzzk.broadcast.BroadcastEndedEvent;
import com.chzzk.broadcast.BroadcastService;
import com.chzzk.broadcast.BroadcastStatus;
import com.chzzk.broadcast.BroadcasterKeys;
import com.chzzk.common.NotFoundException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * WebRTC signaling over WebSocket at {@code /ws/live/{broadcastId}}. The server never touches media: it only
 * relays SDP offers/answers and ICE candidates between the broadcaster and one specific viewer.
 *
 * <p>Client to server (JSON, field {@code type}):
 * <ul>
 *   <li>{@code join}: {@code {role: "broadcaster", broadcasterKey}} or {@code {role: "viewer"}}</li>
 *   <li>{@code offer} / {@code answer}: {@code {to?, sdp}}; {@code ice-candidate}: {@code {to?, candidate}}.
 *       The broadcaster must set {@code to} (a viewerId); a viewer's messages always go to the broadcaster.</li>
 *   <li>{@code ping}: keep-alive for proxies that close idle sockets</li>
 * </ul>
 * Server to client: {@code joined} {peerId, viewerCount}, {@code viewer-joined} / {@code viewer-left}
 * {viewerId, viewerCount} (to the broadcaster), relayed {@code offer} / {@code answer} / {@code ice-candidate}
 * with {@code from}, {@code broadcast-ended}, {@code error} {code, message}, {@code pong}.
 *
 * <p>Room state is in memory, so signaling assumes a single backend instance.
 */
@Component
public class LiveSignalingHandler extends TextWebSocketHandler {

    static final String BROADCASTER_ID = "broadcaster";
    private static final int MAX_MESSAGE_BYTES = 64 * 1024; // Tomcat's 8 KB default can be too small for an SDP

    private static final Logger log = LoggerFactory.getLogger(LiveSignalingHandler.class);

    /** Incoming message. Unknown fields are ignored; SDP and candidate stay raw JSON because we only relay them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Inbound(String type, String role, String broadcasterKey, String to, JsonNode sdp, JsonNode candidate) {
    }

    private final BroadcastService broadcasts;
    private final ObjectMapper mapper;
    private final long graceSeconds;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-grace");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();   // by WebSocket session id
    private final Map<Long, LiveRoom> rooms = new ConcurrentHashMap<>(); // by broadcast id

    public LiveSignalingHandler(BroadcastService broadcasts, ObjectMapper mapper,
                                @Value("${app.live.broadcaster-grace-seconds:15}") long graceSeconds) {
        this.broadcasts = broadcasts;
        this.mapper = mapper;
        this.graceSeconds = graceSeconds;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ---- WebSocket lifecycle -------------------------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (session instanceof NativeWebSocketSession nativeSession) {
            jakarta.websocket.Session raw = nativeSession.getNativeSession(jakarta.websocket.Session.class);
            if (raw != null) {
                raw.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
            }
        }
        peers.put(session.getId(), new Peer(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Peer peer = peers.get(session.getId());
        if (peer == null) {
            return;
        }
        Inbound in;
        try {
            in = mapper.readValue(message.getPayload(), Inbound.class);
        } catch (RuntimeException e) {
            in = null;
        }
        if (in == null || in.type() == null) {
            sendError(peer, "INVALID_MESSAGE", "메시지 형식이 올바르지 않습니다.");
            return;
        }
        switch (in.type()) {
            case "join" -> join(peer, in);
            case "offer", "answer", "ice-candidate" -> relay(peer, in);
            case "ping" -> peer.send(json("type", "pong"));
            default -> sendError(peer, "UNKNOWN_TYPE", "알 수 없는 메시지 유형입니다: " + in.type());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Peer peer = peers.remove(session.getId());
        if (peer == null || !peer.joined()) {
            return;
        }
        LiveRoom room = rooms.get(peer.broadcastId());
        if (room == null) {
            return;
        }
        synchronized (room) {
            if (peer.role() == Peer.Role.VIEWER) {
                if (room.viewers.remove(peer.peerId()) != null && room.broadcaster != null) {
                    room.broadcaster.send(json("type", "viewer-left", "viewerId", peer.peerId(),
                            "viewerCount", room.viewers.size()));
                }
            } else if (room.broadcaster == peer) { // a replaced (older) broadcaster socket is ignored
                room.broadcaster = null;
                startGrace(peer.broadcastId(), room);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on session {}: {}", session.getId(), exception.toString());
    }

    // ---- join ----------------------------------------------------------------------------------------

    private void join(Peer peer, Inbound in) {
        if (peer.joined()) {
            sendError(peer, "ALREADY_JOINED", "이미 참가했습니다.");
            return;
        }
        long broadcastId;
        Broadcast broadcast;
        try {
            broadcastId = broadcastIdOf(peer.session());
            broadcast = broadcasts.get(broadcastId);
        } catch (NotFoundException | IllegalArgumentException e) {
            fail(peer, "NOT_FOUND", "방송을 찾을 수 없습니다.");
            return;
        }
        if (!broadcast.isLiveType()) {
            fail(peer, "NOT_LIVE", "라이브 방송이 아닙니다.");
            return;
        }
        if ("broadcaster".equals(in.role())) {
            joinBroadcaster(peer, broadcast, in.broadcasterKey());
        } else if ("viewer".equals(in.role())) {
            joinViewer(peer, broadcast);
        } else {
            sendError(peer, "INVALID_ROLE", "role은 broadcaster 또는 viewer여야 합니다.");
        }
    }

    private void joinBroadcaster(Peer peer, Broadcast broadcast, String key) {
        long id = broadcast.getId();
        if (!BroadcasterKeys.matches(key, broadcast.getBroadcasterKeyHash())) {
            fail(peer, "FORBIDDEN", "방송자 키가 올바르지 않습니다.");
            return;
        }
        if (broadcasts.markLive(id).getStatus() == BroadcastStatus.ENDED) { // READY -> LIVE, or reconnect while LIVE
            fail(peer, "BROADCAST_ENDED", "이미 종료된 방송입니다.");
            return;
        }
        LiveRoom room = rooms.computeIfAbsent(id, k -> new LiveRoom());
        synchronized (room) {
            if (room.closed) {
                fail(peer, "BROADCAST_ENDED", "이미 종료된 방송입니다.");
                return;
            }
            room.cancelGrace();
            Peer previous = room.broadcaster;
            room.broadcaster = peer;
            peer.assign(Peer.Role.BROADCASTER, id, BROADCASTER_ID);
            if (previous != null && previous != peer) {
                previous.close(CloseStatus.POLICY_VIOLATION.withReason("replaced by a new broadcaster connection"));
            }
            peer.send(json("type", "joined", "role", "broadcaster", "peerId", BROADCASTER_ID,
                    "viewerCount", room.viewers.size()));
            // Viewers that were waiting (e.g. during a reconnect) get a fresh negotiation.
            room.viewers.keySet().forEach(viewerId -> peer.send(json("type", "viewer-joined", "viewerId", viewerId,
                    "viewerCount", room.viewers.size())));
        }
    }

    private void joinViewer(Peer peer, Broadcast broadcast) {
        long id = broadcast.getId();
        if (broadcast.getStatus() == BroadcastStatus.ENDED) {
            fail(peer, "BROADCAST_ENDED", "종료된 방송입니다.");
            return;
        }
        if (broadcast.getStatus() != BroadcastStatus.LIVE) {
            fail(peer, "BROADCAST_NOT_LIVE", "아직 방송이 시작되지 않았습니다.");
            return;
        }
        LiveRoom room = rooms.computeIfAbsent(id, k -> new LiveRoom());
        synchronized (room) {
            if (room.closed) {
                fail(peer, "BROADCAST_ENDED", "종료된 방송입니다.");
                return;
            }
            String viewerId = UUID.randomUUID().toString();
            room.viewers.put(viewerId, peer);
            peer.assign(Peer.Role.VIEWER, id, viewerId);
            peer.send(json("type", "joined", "role", "viewer", "peerId", viewerId,
                    "viewerCount", room.viewers.size(), "broadcasterConnected", room.broadcaster != null));
            if (room.broadcaster != null) {
                room.broadcaster.send(json("type", "viewer-joined", "viewerId", viewerId,
                        "viewerCount", room.viewers.size()));
            }
        }
    }

    // ---- relay ---------------------------------------------------------------------------------------

    private void relay(Peer sender, Inbound in) {
        if (!sender.joined()) {
            sendError(sender, "NOT_JOINED", "먼저 join 메시지를 보내야 합니다.");
            return;
        }
        boolean fromBroadcaster = sender.role() == Peer.Role.BROADCASTER;
        // Direction rules: only the broadcaster sends offers, only viewers send answers.
        if (("offer".equals(in.type()) && !fromBroadcaster) || ("answer".equals(in.type()) && fromBroadcaster)) {
            sendError(sender, "INVALID_DIRECTION", in.type() + " 메시지를 보낼 수 없는 역할입니다.");
            return;
        }
        boolean needsSdp = !"ice-candidate".equals(in.type());
        if (needsSdp && (in.sdp() == null || in.sdp().isNull())) {
            sendError(sender, "INVALID_MESSAGE", "sdp가 필요합니다.");
            return;
        }
        if (fromBroadcaster && (in.to() == null || in.to().isBlank())) {
            sendError(sender, "MISSING_TARGET", "수신 대상(to)이 필요합니다.");
            return;
        }
        LiveRoom room = rooms.get(sender.broadcastId());
        if (room == null) {
            sendError(sender, "BROADCAST_ENDED", "종료된 방송입니다.");
            return;
        }
        Peer target;
        synchronized (room) {
            target = fromBroadcaster ? room.viewers.get(in.to()) : room.broadcaster;
        }
        if (target == null) {
            sendError(sender, fromBroadcaster ? "PEER_NOT_FOUND" : "BROADCASTER_UNAVAILABLE",
                    fromBroadcaster ? "대상 시청자를 찾을 수 없습니다." : "방송자가 연결되어 있지 않습니다.");
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", in.type());
        out.put("from", sender.peerId());
        if (needsSdp) {
            out.put("sdp", in.sdp());
        } else {
            out.put("candidate", in.candidate());
        }
        target.send(mapper.writeValueAsString(out));
    }

    // ---- ending --------------------------------------------------------------------------------------

    /** Starts the countdown that ends the broadcast unless the broadcaster reconnects (call under the room lock). */
    private void startGrace(long broadcastId, LiveRoom room) {
        room.cancelGrace();
        room.graceTask = scheduler.schedule(() -> endAfterGrace(broadcastId, room), graceSeconds, TimeUnit.SECONDS);
    }

    private void endAfterGrace(long broadcastId, LiveRoom room) {
        try {
            synchronized (room) {
                if (room.broadcaster != null || room.closed) {
                    return; // reconnected in time, or already ended some other way
                }
                broadcasts.endBySystem(broadcastId); // publishes BroadcastEndedEvent -> onBroadcastEnded
            }
        } catch (NotFoundException e) {
            log.debug("Broadcast {} no longer exists; nothing to end", broadcastId);
        } catch (RuntimeException e) {
            log.warn("Could not end broadcast {} after the grace period", broadcastId, e);
        }
    }

    /** Notifies everyone in the room and disconnects them. Fired for REST ends and grace-period ends alike. */
    @EventListener
    void onBroadcastEnded(BroadcastEndedEvent event) {
        LiveRoom room = rooms.remove(event.broadcastId());
        if (room == null) {
            return;
        }
        synchronized (room) {
            room.closed = true;
            room.cancelGrace();
            String message = json("type", "broadcast-ended");
            if (room.broadcaster != null) {
                room.broadcaster.send(message);
                room.broadcaster.close(CloseStatus.NORMAL);
            }
            room.viewers.values().forEach(viewer -> {
                viewer.send(message);
                viewer.close(CloseStatus.NORMAL);
            });
            room.broadcaster = null;
            room.viewers.clear();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static long broadcastIdOf(WebSocketSession session) {
        if (session.getUri() == null) {
            throw new IllegalArgumentException("missing uri");
        }
        String path = session.getUri().getPath();
        return Long.parseLong(path.substring(path.lastIndexOf('/') + 1)); // NumberFormatException is an IllegalArgumentException
    }

    private void sendError(Peer peer, String code, String message) {
        peer.send(json("type", "error", "code", code, "message", message));
    }

    /** Sends an error and closes the socket (the peer cannot continue). */
    private void fail(Peer peer, String code, String message) {
        sendError(peer, code, message);
        peer.close(CloseStatus.POLICY_VIOLATION.withReason(code));
    }

    private String json(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return mapper.writeValueAsString(map);
    }
}
