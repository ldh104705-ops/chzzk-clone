package com.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chzzk.RedisTestContainer;
import com.chzzk.TestUsers;
import com.chzzk.TestcontainersConfiguration;
import com.chzzk.broadcast.Broadcast;
import com.chzzk.broadcast.BroadcastRepository;
import com.chzzk.broadcast.BroadcastService;
import com.chzzk.broadcast.BroadcastStatus;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cluster-mode signaling. Two {@link LiveSignalingHandler}s ("instance A" and "instance B"), each with its own
 * {@link RedisLiveCoordinator} and its own sockets, share one real Redis and one real PostgreSQL, exactly like two
 * pods would. A broadcaster connected to A and a viewer connected to B must still find each other.
 */
@SpringBootTest(properties = {
        "app.cluster.mode=redis",
        "app.jwt.secret=0123456789abcdef0123456789abcdef-cluster-test",
        "app.live.broadcaster-grace-seconds=1",
        "app.live.reaper-interval-seconds=3600"}) // the test drives the reaper itself
@Import(TestcontainersConfiguration.class)
class LiveClusterTest {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", RedisTestContainer::host);
        registry.add("spring.data.redis.port", RedisTestContainer::port);
    }

    @Autowired BroadcastService service;
    @Autowired BroadcastRepository repository;
    @Autowired TestUsers users;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired ObjectMapper mapper;
    @Autowired RedisConnectionFactory redisFactory;
    @Autowired StringRedisTemplate redis;

    private TestUsers.Account owner;
    private final List<RedisLiveCoordinator> coordinators = new ArrayList<>();
    private final List<LiveSignalingHandler> handlers = new ArrayList<>();
    private LiveSignalingHandler instanceA;
    private LiveSignalingHandler instanceB;
    private RedisLiveCoordinator coordinatorB;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        owner = users.register("owner");
        instanceA = newInstance(false);
        instanceB = newInstance(true);
    }

    @AfterEach
    void tearDown() {
        handlers.forEach(LiveSignalingHandler::shutdown);
        coordinators.forEach(RedisLiveCoordinator::stop);
    }

    private LiveSignalingHandler newInstance(boolean isB) {
        var coordinator = new RedisLiveCoordinator(redisFactory, redis, mapper, 10);
        var handler = new LiveSignalingHandler(service, jwtDecoder, mapper, 1, coordinator, 1);
        handler.init();
        coordinators.add(coordinator);
        handlers.add(handler);
        if (isB) {
            coordinatorB = coordinator;
        }
        return handler;
    }

    /** A fake WebSocket connection: records what the server sends, and whether it was closed. */
    private final class Conn {
        final WebSocketSession session = mock(WebSocketSession.class);
        final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        final LiveSignalingHandler handler;
        volatile boolean open = true;

        Conn(LiveSignalingHandler handler, long broadcastId) throws Exception {
            this.handler = handler;
            when(session.getId()).thenReturn(UUID.randomUUID().toString());
            when(session.getUri()).thenReturn(URI.create("ws://test/ws/live/" + broadcastId));
            when(session.isOpen()).thenAnswer(inv -> open);
            doAnswer(inv -> {
                received.add(mapper.readTree(((TextMessage) inv.getArgument(0)).getPayload()));
                return null;
            }).when(session).sendMessage(any());
            doAnswer(inv -> {
                open = false;
                return null;
            }).when(session).close(any(CloseStatus.class));
            handler.afterConnectionEstablished(session);
        }

        void send(String json) throws Exception {
            handler.handleMessage(session, new TextMessage(json));
        }

        void disconnect() {
            open = false;
            handler.afterConnectionClosed(session, CloseStatus.GOING_AWAY);
        }

        JsonNode expect(String type) throws Exception {
            JsonNode m = received.poll(5, TimeUnit.SECONDS);
            assertThat(m).as("expected a '%s' message within 5s", type).isNotNull();
            assertThat(m.get("type").asString()).as("message: %s", m).isEqualTo(type);
            return m;
        }

        void expectNothing() throws Exception {
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).as("no message expected").isNull();
        }
    }

    private Conn broadcaster(LiveSignalingHandler on, long id) throws Exception {
        Conn c = new Conn(on, id);
        c.send("{\"type\":\"join\",\"role\":\"broadcaster\",\"token\":\"" + owner.token() + "\"}");
        c.expect("joined");
        return c;
    }

    private Conn viewer(LiveSignalingHandler on, long id) throws Exception {
        Conn c = new Conn(on, id);
        c.send("{\"type\":\"join\",\"role\":\"viewer\"}");
        return c;
    }

    private BroadcastStatus statusOf(long id) {
        return repository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void broadcasterOnOneInstanceAndViewerOnAnotherNegotiateTheConnection() throws Exception {
        long id = service.createLive(owner.id(), "클러스터").getId();
        Conn broadcaster = broadcaster(instanceA, id);
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.LIVE);

        Conn viewer = viewer(instanceB, id);
        JsonNode joined = viewer.expect("joined");
        assertThat(joined.get("broadcasterConnected").asBoolean()).isTrue(); // presence read from Redis
        String viewerId = joined.get("peerId").asString();

        // viewer-joined crosses from B to A through Redis pub/sub
        JsonNode announced = broadcaster.expect("viewer-joined");
        assertThat(announced.get("viewerId").asString()).isEqualTo(viewerId);
        assertThat(announced.get("viewerCount").asInt()).isEqualTo(1);

        // offer: A -> B, answer: B -> A, ICE both ways
        broadcaster.send("{\"type\":\"offer\",\"to\":\"" + viewerId + "\",\"sdp\":{\"type\":\"offer\",\"sdp\":\"v=0-offer\"}}");
        JsonNode offer = viewer.expect("offer");
        assertThat(offer.get("from").asString()).isEqualTo("broadcaster");
        assertThat(offer.get("sdp").get("sdp").asString()).isEqualTo("v=0-offer");

        viewer.send("{\"type\":\"answer\",\"sdp\":{\"type\":\"answer\",\"sdp\":\"v=0-answer\"}}");
        JsonNode answer = broadcaster.expect("answer");
        assertThat(answer.get("from").asString()).isEqualTo(viewerId);
        assertThat(answer.get("sdp").get("sdp").asString()).isEqualTo("v=0-answer");

        viewer.send("{\"type\":\"ice-candidate\",\"candidate\":{\"candidate\":\"from-viewer\"}}");
        assertThat(broadcaster.expect("ice-candidate").get("candidate").get("candidate").asString()).isEqualTo("from-viewer");
        broadcaster.send("{\"type\":\"ice-candidate\",\"to\":\"" + viewerId + "\",\"candidate\":{\"candidate\":\"from-broadcaster\"}}");
        assertThat(viewer.expect("ice-candidate").get("candidate").get("candidate").asString()).isEqualTo("from-broadcaster");

        // a second viewer on B that is not addressed sees nothing
        Conn other = viewer(instanceB, id);
        other.expect("joined");
        broadcaster.expect("viewer-joined");
        broadcaster.send("{\"type\":\"offer\",\"to\":\"" + viewerId + "\",\"sdp\":{\"type\":\"offer\",\"sdp\":\"x\"}}");
        viewer.expect("offer");
        other.expectNothing();

        viewer.disconnect();
        JsonNode left = broadcaster.expect("viewer-left");
        assertThat(left.get("viewerId").asString()).isEqualTo(viewerId);
        assertThat(left.get("viewerCount").asInt()).isEqualTo(1); // `other` is still watching
    }

    @Test
    void viewerCountSpansInstances() throws Exception {
        long id = service.createLive(owner.id(), "시청자 수").getId();
        Conn broadcaster = broadcaster(instanceA, id);
        viewer(instanceA, id).expect("joined");
        broadcaster.expect("viewer-joined");
        Conn onB = viewer(instanceB, id);
        JsonNode joined = onB.expect("joined");
        assertThat(joined.get("viewerCount").asInt()).isEqualTo(2);
        assertThat(broadcaster.expect("viewer-joined").get("viewerCount").asInt()).isEqualTo(2);

        // a broadcaster that reconnects through B learns about the viewer connected to A as well
        broadcaster.disconnect();
        Conn again = new Conn(instanceB, id);
        again.send("{\"type\":\"join\",\"role\":\"broadcaster\",\"token\":\"" + owner.token() + "\"}");
        assertThat(again.expect("joined").get("viewerCount").asInt()).isEqualTo(2);
        again.expect("viewer-joined");
        again.expect("viewer-joined");
    }

    @Test
    void aNewBroadcasterConnectionOnAnotherInstanceReplacesTheOldOne() throws Exception {
        long id = service.createLive(owner.id(), "교체").getId();
        Conn first = broadcaster(instanceA, id);
        Conn second = broadcaster(instanceB, id);
        await().atMost(Duration.ofSeconds(5)).until(() -> !first.open);
        assertThat(second.open).isTrue();
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.LIVE);
    }

    @Test
    void endingTheBroadcastNotifiesAndDisconnectsPeersOnEveryInstance() throws Exception {
        long id = service.createLive(owner.id(), "종료").getId();
        Conn broadcaster = broadcaster(instanceA, id);
        Conn viewerB = viewer(instanceB, id);
        viewerB.expect("joined");
        broadcaster.expect("viewer-joined");

        service.endLive(id, owner.id()); // REST end handled "somewhere": publishes BroadcastEndedEvent

        viewerB.expect("broadcast-ended");
        broadcaster.expect("broadcast-ended");
        await().atMost(Duration.ofSeconds(5)).until(() -> !viewerB.open && !broadcaster.open);
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.ENDED);
        assertThat(coordinatorB.broadcasterPresent(id)).isFalse();
        assertThat(coordinatorB.viewers(id)).isEmpty();
    }

    @Test
    void reconnectingThroughAnotherInstanceWithinGraceKeepsTheBroadcastLive() throws Exception {
        long id = service.createLive(owner.id(), "재연결").getId();
        Conn first = broadcaster(instanceA, id);
        first.disconnect(); // instance A starts a 1 s grace timer
        Conn second = broadcaster(instanceB, id); // broadcaster is back, on B
        Thread.sleep(2500); // well past the grace period
        assertThat(statusOf(id)).isEqualTo(BroadcastStatus.LIVE);
        assertThat(second.open).isTrue();
    }

    @Test
    void broadcastEndsAfterGraceWhenNobodyIsBroadcastingAnymore() throws Exception {
        long id = service.createLive(owner.id(), "끊김").getId();
        Conn broadcaster = broadcaster(instanceA, id);
        Conn viewerB = viewer(instanceB, id);
        viewerB.expect("joined");
        broadcaster.expect("viewer-joined");

        broadcaster.disconnect();

        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(id) == BroadcastStatus.ENDED);
        viewerB.expect("broadcast-ended");
    }

    @Test
    void reaperEndsOnlyBroadcastsWithoutPresence() {
        Broadcast present = service.createLive(owner.id(), "방송 중");
        Broadcast gone = service.createLive(owner.id(), "주인 없음");
        Broadcast fresh = service.createLive(owner.id(), "준비 중"); // READY, never streamed
        service.markLive(present.getId());
        service.markLive(gone.getId());

        coordinatorB.broadcasterJoined(present.getId(), "some-socket-on-another-pod"); // only this one has presence

        var reaper = new LiveReaper(service, coordinatorB, 0, 3600, 5);
        assertThat(reaper.reapUnlocked()).isEqualTo(1);

        assertThat(statusOf(present.getId())).isEqualTo(BroadcastStatus.LIVE);
        assertThat(statusOf(gone.getId())).isEqualTo(BroadcastStatus.ENDED);
        assertThat(statusOf(fresh.getId())).isEqualTo(BroadcastStatus.READY); // READY is only reaped after ready-ttl
        assertThat(reaper.reapUnlocked()).isZero(); // idempotent

        // With a real grace period, a freshly missing presence is not reaped yet.
        service.markLive(fresh.getId());
        var patient = new LiveReaper(service, coordinatorB, 60, 3600, 5);
        assertThat(patient.reapUnlocked()).isZero();
        assertThat(statusOf(fresh.getId())).isEqualTo(BroadcastStatus.LIVE);
    }

    @Test
    void endingIsAtomicSoOnlyOneInstanceAnnouncesIt() {
        long id = service.createLive(owner.id(), "원자적").getId();
        service.markLive(id);
        assertThat(service.endIfStillActive(id)).isTrue();
        assertThat(service.endIfStillActive(id)).isFalse();
    }
}
