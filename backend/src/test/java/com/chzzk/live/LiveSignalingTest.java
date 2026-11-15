package com.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.chzzk.TestUsers;
import com.chzzk.TestcontainersConfiguration;
import com.chzzk.broadcast.BroadcastRepository;
import com.chzzk.broadcast.BroadcastService;
import com.chzzk.broadcast.Broadcast;
import com.chzzk.broadcast.BroadcastStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signaling integration test: a real server on a random port, real WebSocket clients, real PostgreSQL.
 * The grace period is shortened to 1 second so the "broadcaster disconnected" path can be exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.live.broadcaster-grace-seconds=1")
@Import(TestcontainersConfiguration.class)
class LiveSignalingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    Environment env;

    @Autowired
    BroadcastService service;

    @Autowired
    BroadcastRepository repository;

    @Autowired
    TestUsers users;

    private TestUsers.Account owner;

    private final List<Client> clients = new ArrayList<>();

    @BeforeEach
    void clean() {
        repository.deleteAll();
        owner = users.register("owner");
    }

    @AfterEach
    void closeClients() {
        clients.forEach(Client::closeQuietly);
    }

    /** A WebSocket test client that queues every received JSON message. */
    private static final class Client extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        volatile WebSocketSession session;
        volatile CloseStatus closeStatus;

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            this.session = session;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(JSON.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            this.closeStatus = status;
        }

        void send(String json) throws Exception {
            session.sendMessage(new TextMessage(json));
        }

        JsonNode next() throws InterruptedException {
            JsonNode m = messages.poll(5, TimeUnit.SECONDS);
            assertThat(m).as("expected a message within 5s").isNotNull();
            return m;
        }

        JsonNode expect(String type) throws InterruptedException {
            JsonNode m = next();
            assertThat(m.get("type").asString()).as("message: %s", m).isEqualTo(type);
            return m;
        }

        void expectNothing() throws InterruptedException {
            assertThat(messages.poll(400, TimeUnit.MILLISECONDS)).as("no message expected").isNull();
        }

        void awaitClosed() {
            await().atMost(Duration.ofSeconds(5)).until(() -> closeStatus != null);
        }

        void closeQuietly() {
            try {
                if (session != null && session.isOpen()) {
                    session.close();
                }
            } catch (Exception ignored) {
                // test cleanup only
            }
        }
    }

    private Client connect(long broadcastId) throws Exception {
        return connect(broadcastId, new WebSocketHttpHeaders());
    }

    private Client connect(long broadcastId, WebSocketHttpHeaders headers) throws Exception {
        Client client = new Client();
        String url = "ws://localhost:" + env.getProperty("local.server.port") + "/ws/live/" + broadcastId;
        new StandardWebSocketClient().execute(client, headers, java.net.URI.create(url)).get(5, TimeUnit.SECONDS);
        clients.add(client);
        return client;
    }

    private Client joinBroadcaster(long id, String token) throws Exception {
        Client c = connect(id);
        c.send("{\"type\":\"join\",\"role\":\"broadcaster\",\"token\":\"" + token + "\"}");
        JsonNode joined = c.expect("joined");
        assertThat(joined.get("peerId").asString()).isEqualTo("broadcaster");
        return c;
    }

    private Client joinViewer(long id) throws Exception {
        Client c = connect(id);
        c.send("{\"type\":\"join\",\"role\":\"viewer\"}");
        c.expect("joined");
        return c;
    }

    private static String viewerIdOf(JsonNode viewerJoined) {
        return viewerJoined.get("viewerId").asString();
    }

    private BroadcastStatus statusOf(long id) {
        return repository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void broadcasterJoinMakesTheBroadcastLiveAndViewerJoinIsAnnounced() throws Exception {
        Broadcast live = service.createLive(owner.id(), "시그널링");
        long id = live.getId();
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.READY);

        Client broadcaster = joinBroadcaster(id, owner.token());
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.LIVE);
        assertThat(repository.findById(id).orElseThrow().getStartedAt()).isNotNull();

        Client viewer = connect(id);
        viewer.send("{\"type\":\"join\",\"role\":\"viewer\"}");
        JsonNode viewerJoined = viewer.expect("joined");
        JsonNode announced = broadcaster.expect("viewer-joined");
        assertThat(announced.get("viewerId").asString()).isEqualTo(viewerJoined.get("peerId").asString());
        assertThat(announced.get("viewerCount").asInt()).isEqualTo(1);

        viewer.closeQuietly();
        JsonNode left = broadcaster.expect("viewer-left");
        assertThat(left.get("viewerId").asString()).isEqualTo(viewerIdOf(announced));
        assertThat(left.get("viewerCount").asInt()).isZero();
    }

    @Test
    void relaysOfferAnswerAndIceToTheRightPeerOnly() throws Exception {
        Broadcast live = service.createLive(owner.id(), "릴레이");
        long id = live.getId();
        Client broadcaster = joinBroadcaster(id, owner.token());
        Client viewerA = joinViewer(id);
        String idA = viewerIdOf(broadcaster.expect("viewer-joined"));
        Client viewerB = joinViewer(id);
        String idB = viewerIdOf(broadcaster.expect("viewer-joined"));
        assertThat(idA).isNotEqualTo(idB);

        // Offer for viewer B only: A must not see it.
        broadcaster.send("{\"type\":\"offer\",\"to\":\"" + idB + "\",\"sdp\":{\"type\":\"offer\",\"sdp\":\"v=0-B\"}}");
        JsonNode offer = viewerB.expect("offer");
        assertThat(offer.get("from").asString()).isEqualTo("broadcaster");
        assertThat(offer.get("sdp").get("sdp").asString()).isEqualTo("v=0-B");
        assertThat(offer.has("to")).isFalse();
        viewerA.expectNothing();

        // B's answer goes to the broadcaster, stamped with B's id.
        viewerB.send("{\"type\":\"answer\",\"sdp\":{\"type\":\"answer\",\"sdp\":\"answer-B\"}}");
        JsonNode answer = broadcaster.expect("answer");
        assertThat(answer.get("from").asString()).isEqualTo(idB);
        assertThat(answer.get("sdp").get("sdp").asString()).isEqualTo("answer-B");

        // ICE in both directions.
        viewerB.send("{\"type\":\"ice-candidate\",\"candidate\":{\"candidate\":\"candidate:1 viewer\"}}");
        JsonNode fromViewer = broadcaster.expect("ice-candidate");
        assertThat(fromViewer.get("from").asString()).isEqualTo(idB);
        assertThat(fromViewer.get("candidate").get("candidate").asString()).isEqualTo("candidate:1 viewer");

        broadcaster.send("{\"type\":\"ice-candidate\",\"to\":\"" + idA + "\",\"candidate\":{\"candidate\":\"candidate:2 host\"}}");
        JsonNode toA = viewerA.expect("ice-candidate");
        assertThat(toA.get("from").asString()).isEqualTo("broadcaster");
        assertThat(toA.get("candidate").get("candidate").asString()).isEqualTo("candidate:2 host");
        viewerB.expectNothing();
    }

    @Test
    void rejectsInvalidMessages() throws Exception {
        Broadcast live = service.createLive(owner.id(), "검증");
        long id = live.getId();
        Client broadcaster = joinBroadcaster(id, owner.token());
        Client viewer = joinViewer(id);
        broadcaster.expect("viewer-joined");

        broadcaster.send("not json");
        assertThat(broadcaster.expect("error").get("code").asString()).isEqualTo("INVALID_MESSAGE");
        broadcaster.send("{\"type\":\"offer\",\"sdp\":{\"type\":\"offer\",\"sdp\":\"x\"}}");
        assertThat(broadcaster.expect("error").get("code").asString()).isEqualTo("MISSING_TARGET");
        broadcaster.send("{\"type\":\"offer\",\"to\":\"nobody\",\"sdp\":{\"type\":\"offer\",\"sdp\":\"x\"}}");
        assertThat(broadcaster.expect("error").get("code").asString()).isEqualTo("PEER_NOT_FOUND");
        broadcaster.send("{\"type\":\"nope\"}");
        assertThat(broadcaster.expect("error").get("code").asString()).isEqualTo("UNKNOWN_TYPE");

        viewer.send("{\"type\":\"offer\",\"sdp\":{\"type\":\"offer\",\"sdp\":\"x\"}}"); // viewers never send offers
        assertThat(viewer.expect("error").get("code").asString()).isEqualTo("INVALID_DIRECTION");

        Client stranger = connect(id); // never joined
        stranger.send("{\"type\":\"ice-candidate\",\"candidate\":{}}");
        assertThat(stranger.expect("error").get("code").asString()).isEqualTo("NOT_JOINED");
        broadcaster.expectNothing();
    }

    @Test
    void onlyTheOwnerCanJoinAsBroadcaster() throws Exception {
        Broadcast live = service.createLive(owner.id(), "소유자");
        long id = live.getId();
        TestUsers.Account other = users.register("other");

        Client garbage = connect(id);
        garbage.send("{\"type\":\"join\",\"role\":\"broadcaster\",\"token\":\"not-a-jwt\"}");
        assertThat(garbage.expect("error").get("code").asString()).isEqualTo("UNAUTHORIZED");
        garbage.awaitClosed();

        Client missing = connect(id);
        missing.send("{\"type\":\"join\",\"role\":\"broadcaster\"}");
        assertThat(missing.expect("error").get("code").asString()).isEqualTo("UNAUTHORIZED");
        missing.awaitClosed();

        Client stranger = connect(id); // a valid login, but not the owner
        stranger.send("{\"type\":\"join\",\"role\":\"broadcaster\",\"token\":\"" + other.token() + "\"}");
        assertThat(stranger.expect("error").get("code").asString()).isEqualTo("FORBIDDEN");
        stranger.awaitClosed();

        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.READY); // a failed attempt must not start the broadcast

        Client ownerClient = joinBroadcaster(id, owner.token()); // the owner is accepted
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.LIVE);
        ownerClient.expectNothing();
    }

    @Test
    void viewerCannotJoinABroadcastThatIsNotLive() throws Exception {
        Broadcast live = service.createLive(owner.id(), "종료됨");
        long id = live.getId();

        Client early = connect(id); // still READY: nobody is streaming yet
        early.send("{\"type\":\"join\",\"role\":\"viewer\"}");
        assertThat(early.expect("error").get("code").asString()).isEqualTo("BROADCAST_NOT_LIVE");
        early.awaitClosed();

        service.markLive(id);
        service.endLive(id, owner.id());
        Client late = connect(id);
        late.send("{\"type\":\"join\",\"role\":\"viewer\"}");
        assertThat(late.expect("error").get("code").asString()).isEqualTo("BROADCAST_ENDED");
        late.awaitClosed();

        // An ended broadcast cannot be restarted with the owner token either.
        Client revived = connect(id);
        revived.send("{\"type\":\"join\",\"role\":\"broadcaster\",\"token\":\"" + owner.token() + "\"}");
        assertThat(revived.expect("error").get("code").asString()).isEqualTo("BROADCAST_ENDED");
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.ENDED);

        Client unknown = connect(99999);
        unknown.send("{\"type\":\"join\",\"role\":\"viewer\"}");
        assertThat(unknown.expect("error").get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void endingThroughTheServiceNotifiesAndDisconnectsEveryone() throws Exception {
        Broadcast live = service.createLive(owner.id(), "종료 알림");
        long id = live.getId();
        Client broadcaster = joinBroadcaster(id, owner.token());
        Client viewer = joinViewer(id);
        broadcaster.expect("viewer-joined");

        service.endLive(id, owner.id()); // same code path as POST /api/broadcasts/{id}/end

        viewer.expect("broadcast-ended");
        broadcaster.expect("broadcast-ended");
        viewer.awaitClosed();
        broadcaster.awaitClosed();
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.ENDED);
    }

    @Test
    void broadcasterDisconnectEndsTheBroadcastAfterTheGracePeriod() throws Exception {
        Broadcast live = service.createLive(owner.id(), "끊김");
        long id = live.getId();
        Client broadcaster = joinBroadcaster(id, owner.token());
        Client viewer = joinViewer(id);
        broadcaster.expect("viewer-joined");

        broadcaster.closeQuietly();

        // Still LIVE right after the drop (grace period is 1s), then ENDED and the viewer is told.
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.LIVE);
        viewer.expect("broadcast-ended");
        viewer.awaitClosed();
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.ENDED);
        assertThat(repository.findById(id).orElseThrow().getEndedAt()).isNotNull();
    }

    @Test
    void broadcasterReconnectingWithinGraceKeepsTheBroadcastAndRenegotiatesWithViewers() throws Exception {
        Broadcast live = service.createLive(owner.id(), "재접속");
        long id = live.getId();
        Client first = joinBroadcaster(id, owner.token());
        Client viewer = joinViewer(id);
        String viewerId = viewerIdOf(first.expect("viewer-joined"));

        first.closeQuietly();
        first.awaitClosed();
        Client second = connect(id);
        second.send("{\"type\":\"join\",\"role\":\"broadcaster\",\"token\":\"" + owner.token() + "\"}");
        second.expect("joined");
        assertThat(viewerIdOf(second.expect("viewer-joined"))).isEqualTo(viewerId); // viewer is re-announced

        Thread.sleep(1600); // well past the 1s grace period
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.LIVE);
        viewer.expectNothing(); // viewer was never told the broadcast ended

        second.send("{\"type\":\"offer\",\"to\":\"" + viewerId + "\",\"sdp\":{\"type\":\"offer\",\"sdp\":\"again\"}}");
        assertThat(viewer.expect("offer").get("sdp").get("sdp").asString()).isEqualTo("again");
    }

    @Test
    void rejectsWebSocketHandshakeFromDisallowedOrigin() {
        Broadcast live = service.createLive(owner.id(), "오리진");
        long id = live.getId();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://evil.example.com");

        assertThatThrownBy(() -> connect(id, headers)).isNotNull();

        // The configured frontend origin (app.cors.allowed-origins default) is accepted.
        WebSocketHttpHeaders allowed = new WebSocketHttpHeaders();
        allowed.setOrigin("http://localhost:5173");
        assertThat(connectOrNull(id, allowed)).isNotNull();
    }

    private Client connectOrNull(long id, WebSocketHttpHeaders headers) {
        try {
            return connect(id, headers);
        } catch (Exception e) {
            return null;
        }
    }
}
