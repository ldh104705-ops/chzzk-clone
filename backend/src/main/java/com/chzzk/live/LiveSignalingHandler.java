package com.chzzk.live;

import com.chzzk.broadcast.Broadcast;
import com.chzzk.broadcast.BroadcastEndedEvent;
import com.chzzk.broadcast.BroadcastService;
import com.chzzk.broadcast.BroadcastStatus;
import com.chzzk.common.ForbiddenException;
import com.chzzk.common.NotFoundException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
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
 *   <li>{@code join}: {@code {role: "broadcaster", token} (token = the owner's login JWT)} or {@code {role: "viewer"}}</li>
 *   <li>{@code offer} / {@code answer}: {@code {to?, sdp}}; {@code ice-candidate}: {@code {to?, candidate}}.
 *       The broadcaster must set {@code to} (a viewerId); a viewer's messages always go to the broadcaster.</li>
 *   <li>{@code ping}: keep-alive for proxies that close idle sockets</li>
 * </ul>
 * Server to client: {@code joined} {peerId, viewerCount}, {@code viewer-joined} / {@code viewer-left}
 * {viewerId, viewerCount} (to the broadcaster), relayed {@code offer} / {@code answer} / {@code ice-candidate}
 * with {@code from}, {@code broadcast-ended}, {@code error} {code, message}, {@code pong}.
 *
 * <p>Each instance only holds its own sockets ({@link LiveRoom}). Presence (who broadcasts, who watches) and message
 * delivery between instances go through a {@link LiveCoordinator}: in memory in local mode, Redis in cluster mode,
 * where the broadcaster and each viewer may be connected to different backend instances.
 */
@Component
public class LiveSignalingHandler extends TextWebSocketHandler {

    static final String BROADCASTER_ID = "broadcaster";
    private static final int MAX_MESSAGE_BYTES = 64 * 1024; // Tomcat's 8 KB default can be too small for an SDP

    private static final Logger log = LoggerFactory.getLogger(LiveSignalingHandler.class);

    /** Incoming message. Unknown fields are ignored; SDP and candidate stay raw JSON because we only relay them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Inbound(String type, String role, String token, String to, JsonNode sdp, JsonNode candidate) {
    }

    private final BroadcastService broadcasts;
    private final JwtDecoder jwtDecoder;
    private final ObjectMapper mapper;
    private final long graceSeconds;
    private final LiveCoordinator coordinator;
    private final long heartbeatSeconds;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-grace");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();   // by WebSocket session id
    private final Map<Long, LiveRoom> rooms = new ConcurrentHashMap<>(); // by broadcast id (sockets of THIS instance only)

    public LiveSignalingHandler(BroadcastService broadcasts, JwtDecoder jwtDecoder, ObjectMapper mapper,
                                @Value("${app.live.broadcaster-grace-seconds:15}") long graceSeconds,
                                LiveCoordinator coordinator,
                                @Value("${app.live.heartbeat-seconds:3}") long heartbeatSeconds) {
        this.broadcasts = broadcasts;
        this.jwtDecoder = jwtDecoder;
        this.mapper = mapper;
        this.graceSeconds = graceSeconds;
        this.coordinator = coordinator;
        this.heartbeatSeconds = heartbeatSeconds;
    }

    @PostConstruct
    void init() {
        coordinator.subscribe(this::deliver);
        scheduler.scheduleWithFixedDelay(this::heartbeat, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** Keeps the shared presence of the sockets held by THIS instance alive (a dead instance's presence expires). */
    private void heartbeat() {
        try {
            rooms.forEach((id, room) -> {
                Peer broadcaster;
                List<String> viewerIds;
                synchronized (room) {
                    broadcaster = room.broadcaster;
                    viewerIds = List.copyOf(room.viewers.keySet());
                }
                if (broadcaster != null) {
                    coordinator.broadcasterHeartbeat(id, broadcaster.session().getId());
                }
                viewerIds.forEach(viewerId -> coordinator.viewerHeartbeat(id, viewerId));
            });
        } catch (RuntimeException e) {
            log.warn("Live presence heartbeat failed", e);
        }
    }

    /** Delivers an envelope (from this or any other instance) to the sockets this instance holds. */
    void deliver(LiveCoordinator.Envelope env) {
        LiveRoom room = LiveCoordinator.ALL.equals(env.to()) ? rooms.remove(env.broadcastId()) : rooms.get(env.broadcastId());
        if (room == null) {
            return;
        }
        synchronized (room) {
            switch (env.to()) {
                case LiveCoordinator.ALL -> { // broadcast ended: tell everybody and disconnect them
                    room.closed = true;
                    room.cancelGrace();
                    if (room.broadcaster != null) {
                        room.broadcaster.send(env.payload());
                        room.broadcaster.close(CloseStatus.NORMAL);
                    }
                    room.viewers.values().forEach(viewer -> {
                        viewer.send(env.payload());
                        viewer.close(CloseStatus.NORMAL);
                    });
                    room.broadcaster = null;
                    room.viewers.clear();
                }
                case LiveCoordinator.REPLACE -> { // a newer broadcaster connection (maybe on another instance) took over
                    if (room.broadcaster != null && !room.broadcaster.session().getId().equals(env.exceptToken())) {
                        room.broadcaster.close(CloseStatus.POLICY_VIOLATION.withReason("replaced by a new broadcaster connection"));
                    }
                }
                case LiveCoordinator.BROADCASTER -> {
                    if (room.broadcaster != null) {
                        room.broadcaster.send(env.payload());
                    }
                }
                default -> {
                    Peer viewer = room.viewers.get(env.to());
                    if (viewer != null) {
                        viewer.send(env.payload());
                    }
                }
            }
        }
    }

    private void toBroadcaster(long broadcastId, String payload) {
        coordinator.send(new LiveCoordinator.Envelope(broadcastId, LiveCoordinator.BROADCASTER, payload, null));
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
                if (room.viewers.remove(peer.peerId()) != null) {
                    coordinator.viewerLeft(peer.broadcastId(), peer.peerId());
                    toBroadcaster(peer.broadcastId(), json("type", "viewer-left", "viewerId", peer.peerId(),
                            "viewerCount", coordinator.viewers(peer.broadcastId()).size()));
                }
            } else if (room.broadcaster == peer) { // a replaced (older) broadcaster socket is ignored
                room.broadcaster = null;
                coordinator.broadcasterLeft(peer.broadcastId(), session.getId()); // no-op if a newer connection owns it
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
            joinBroadcaster(peer, broadcast, in.token());
        } else if ("viewer".equals(in.role())) {
            joinViewer(peer, broadcast);
        } else {
            sendError(peer, "INVALID_ROLE", "role은 broadcaster 또는 viewer여야 합니다.");
        }
    }

    /**
     * Only the broadcast's owner may stream: the join message carries the login JWT ({@code token}). It is checked
     * once, at join time, and never logged. A missing/invalid/expired/logged-out token is UNAUTHORIZED, a valid token
     * of somebody else is FORBIDDEN.
     */
    private void joinBroadcaster(Peer peer, Broadcast broadcast, String token) {
        long id = broadcast.getId();
        long userId;
        try {
            if (token == null || token.isBlank()) {
                throw new JwtException("missing token");
            }
            userId = Long.parseLong(jwtDecoder.decode(token).getSubject());
        } catch (JwtException | NumberFormatException e) {
            fail(peer, "UNAUTHORIZED", "로그인이 필요하거나 토큰이 올바르지 않습니다.");
            return;
        }
        try {
            broadcasts.authorizeBroadcaster(id, userId);
        } catch (ForbiddenException e) {
            fail(peer, "FORBIDDEN", "방송 소유자만 송출할 수 있습니다.");
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
            String sessionToken = peer.session().getId();
            coordinator.broadcasterJoined(id, sessionToken);
            // A previous broadcaster connection may be held by another instance: tell all of them to drop it.
            coordinator.send(new LiveCoordinator.Envelope(id, LiveCoordinator.REPLACE, "", sessionToken));
            List<String> waiting = coordinator.viewers(id); // viewers on ANY instance
            peer.send(json("type", "joined", "role", "broadcaster", "peerId", BROADCASTER_ID,
                    "viewerCount", waiting.size()));
            // Viewers that were waiting (e.g. during a reconnect) get a fresh negotiation.
            waiting.forEach(viewerId -> peer.send(json("type", "viewer-joined", "viewerId", viewerId,
                    "viewerCount", waiting.size())));
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
            coordinator.viewerJoined(id, viewerId);
            int viewerCount = coordinator.viewers(id).size();
            peer.send(json("type", "joined", "role", "viewer", "peerId", viewerId,
                    "viewerCount", viewerCount, "broadcasterConnected", coordinator.broadcasterPresent(id)));
            // Delivered by whichever instance holds the broadcaster socket (dropped if there is none).
            toBroadcaster(id, json("type", "viewer-joined", "viewerId", viewerId, "viewerCount", viewerCount));
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
        // The target may be connected to another instance, so existence is checked in the shared presence data.
        long broadcastId = sender.broadcastId();
        boolean targetPresent = fromBroadcaster ? coordinator.hasViewer(broadcastId, in.to())
                : coordinator.broadcasterPresent(broadcastId);
        if (!targetPresent) {
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
        coordinator.send(new LiveCoordinator.Envelope(broadcastId,
                fromBroadcaster ? in.to() : LiveCoordinator.BROADCASTER, mapper.writeValueAsString(out), null));
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
            }
            if (coordinator.broadcasterPresent(broadcastId)) {
                return; // the broadcaster reconnected through another instance
            }
            // Conditional UPDATE: if several instances got here, exactly one ends it and publishes BroadcastEndedEvent.
            broadcasts.endIfStillActive(broadcastId);
        } catch (RuntimeException e) {
            log.warn("Could not end broadcast {} after the grace period", broadcastId, e);
        }
    }

    /** Tells every instance to notify and disconnect the peers of the broadcast. Fired for REST ends, grace-period ends and reaper ends alike. */
    @EventListener
    void onBroadcastEnded(BroadcastEndedEvent event) {
        // Published to every instance (including this one); each closes the sockets it holds (see deliver).
        coordinator.send(new LiveCoordinator.Envelope(event.broadcastId(), LiveCoordinator.ALL,
                json("type", "broadcast-ended"), null));
        coordinator.clear(event.broadcastId());
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
