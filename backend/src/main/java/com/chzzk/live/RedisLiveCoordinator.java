package com.chzzk.live;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Cluster-mode coordinator. Shared state lives in Redis, so a broadcaster and its viewers may be connected to
 * different backend instances:
 * <ul>
 *   <li>{@code live:{id}:presence}: the broadcaster socket's token, with a short TTL that its instance keeps
 *       refreshing (heartbeat). If the instance dies the key simply expires and the broadcast can be reaped.</li>
 *   <li>{@code live:{id}:viewers}: a sorted set (viewer id, last heartbeat time); stale entries are dropped on read.</li>
 *   <li>{@code live:{id}:absent}: when the presence was first seen missing (see {@link #absentLongerThan}); the
 *       reaper uses it to apply the grace period without any instance holding a timer.</li>
 *   <li>pub/sub channel {@code chzzk:live}: every {@link Envelope} is published once; every instance receives it and
 *       delivers it to the sockets it holds (offer/answer/ICE, viewer-joined, broadcast-ended, ...).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "redis")
class RedisLiveCoordinator implements LiveCoordinator {

    static final String CHANNEL = "chzzk:live";
    private static final Logger log = LoggerFactory.getLogger(RedisLiveCoordinator.class);

    /** Sets the presence if it is free or already ours, and refreshes the TTL; returns 0 if somebody else holds it. */
    private static final DefaultRedisScript<Long> ASSERT_PRESENCE = new DefaultRedisScript<>("""
            local v = redis.call('get', KEYS[1])
            if v == false then
              redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]); return 1
            elseif v == ARGV[1] then
              redis.call('expire', KEYS[1], ARGV[2]); return 1
            end
            return 0
            """, Long.class);

    /** Deletes the presence only if it is still ours. */
    private static final DefaultRedisScript<Long> RELEASE_PRESENCE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end
            return 0
            """, Long.class);

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final long presenceTtlSeconds;
    private final long viewerTtlMillis;
    private RedisMessageListenerContainer container;

    RedisLiveCoordinator(RedisConnectionFactory connectionFactory, StringRedisTemplate redis, ObjectMapper mapper,
                         @Value("${app.live.presence-ttl-seconds:10}") long presenceTtlSeconds) {
        this.connectionFactory = connectionFactory;
        this.redis = redis;
        this.mapper = mapper;
        this.presenceTtlSeconds = presenceTtlSeconds;
        this.viewerTtlMillis = presenceTtlSeconds * 3000; // a viewer is dropped after ~3 missed heartbeat windows
    }

    private static String presenceKey(long id) { return "live:" + id + ":presence"; }
    private static String viewersKey(long id) { return "live:" + id + ":viewers"; }
    private static String absentKey(long id) { return "live:" + id + ":absent"; }

    // ---- pub/sub -------------------------------------------------------------------------------------

    @Override
    public synchronized void subscribe(Consumer<Envelope> localDelivery) {
        stop();
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.afterPropertiesSet();
        container.addMessageListener((Message message, byte[] pattern) -> {
            try {
                localDelivery.accept(mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), Envelope.class));
            } catch (RuntimeException e) {
                log.warn("Could not deliver a live signaling message", e);
            }
        }, new ChannelTopic(CHANNEL));
        container.start();
    }

    @PreDestroy
    synchronized void stop() {
        if (container != null) {
            try {
                container.destroy();
            } catch (Exception e) {
                log.debug("Error while stopping the Redis listener container", e);
            }
            container = null;
        }
    }

    @Override
    public void send(Envelope envelope) {
        redis.convertAndSend(CHANNEL, mapper.writeValueAsString(envelope));
    }

    // ---- broadcaster presence ------------------------------------------------------------------------

    @Override
    public void broadcasterJoined(long id, String token) {
        redis.opsForValue().set(presenceKey(id), token, Duration.ofSeconds(presenceTtlSeconds)); // newest connection wins
        redis.delete(absentKey(id));
    }

    @Override
    public void broadcasterHeartbeat(long id, String token) {
        redis.execute(ASSERT_PRESENCE, List.of(presenceKey(id)), token, String.valueOf(presenceTtlSeconds));
    }

    @Override
    public void broadcasterLeft(long id, String token) {
        redis.execute(RELEASE_PRESENCE, List.of(presenceKey(id)), token);
    }

    @Override
    public boolean broadcasterPresent(long id) {
        return Boolean.TRUE.equals(redis.hasKey(presenceKey(id)));
    }

    // ---- viewers -------------------------------------------------------------------------------------

    @Override
    public void viewerJoined(long id, String viewerId) {
        viewerHeartbeat(id, viewerId);
    }

    @Override
    public void viewerHeartbeat(long id, String viewerId) {
        redis.opsForZSet().add(viewersKey(id), viewerId, System.currentTimeMillis());
        redis.expire(viewersKey(id), Duration.ofSeconds(presenceTtlSeconds * 6));
    }

    @Override
    public void viewerLeft(long id, String viewerId) {
        redis.opsForZSet().remove(viewersKey(id), viewerId);
    }

    @Override
    public List<String> viewers(long id) {
        redis.opsForZSet().removeRangeByScore(viewersKey(id), Double.NEGATIVE_INFINITY,
                System.currentTimeMillis() - viewerTtlMillis);
        Set<String> members = redis.opsForZSet().range(viewersKey(id), 0, -1);
        return members == null ? List.of() : List.copyOf(members);
    }

    @Override
    public boolean hasViewer(long id, String viewerId) {
        Double score = redis.opsForZSet().score(viewersKey(id), viewerId);
        return score != null && score >= System.currentTimeMillis() - viewerTtlMillis;
    }

    @Override
    public void clear(long id) {
        redis.delete(List.of(presenceKey(id), viewersKey(id), absentKey(id)));
    }

    // ---- reaper support ------------------------------------------------------------------------------

    /**
     * True if the broadcaster presence has been missing for longer than {@code grace}. The first call that finds the
     * presence missing records the time (SET NX, so concurrent instances agree); a present broadcaster resets it.
     */
    boolean absentLongerThan(long id, Duration grace) {
        if (broadcasterPresent(id)) {
            redis.delete(absentKey(id));
            return false;
        }
        long now = System.currentTimeMillis();
        redis.opsForValue().setIfAbsent(absentKey(id), String.valueOf(now), Duration.ofDays(1));
        String since = redis.opsForValue().get(absentKey(id));
        return since != null && now - Long.parseLong(since) >= grace.toMillis();
    }

    /** Cluster-wide lock so only one instance reaps per cycle (the conditional DB update is the real safety net). */
    boolean tryLock(String name, Duration ttl, String owner) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("lock:" + name, owner, ttl));
    }
}
