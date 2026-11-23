package com.chzzk.live;

import com.chzzk.broadcast.Broadcast;
import com.chzzk.broadcast.BroadcastService;
import com.chzzk.broadcast.BroadcastStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Cluster-mode replacement for {@link LiveStartupCleanup}. A new pod starting says nothing about live broadcasts
 * that are streaming through other pods, so nothing is ended at startup. Instead every instance periodically
 * looks for READY/LIVE rows whose broadcaster presence (a Redis key with a heartbeat TTL) has been missing for
 * longer than the grace period, which covers a broadcaster whose pod was killed (no pod is left to run its grace
 * timer). A Redis lock keeps the instances from all scanning at once, and the conditional UPDATE in
 * {@link BroadcastService#endIfStillActive} makes ending idempotent even if two did.
 *
 * <p>LIVE rows: ended after {@code broadcaster-grace-seconds} without presence. READY rows (created, never
 * streamed) are ended after {@code ready-ttl-seconds} (default 1 hour) without presence, measured from creation.
 */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "redis")
class LiveReaper {

    private static final Logger log = LoggerFactory.getLogger(LiveReaper.class);

    private final BroadcastService broadcasts;
    private final RedisLiveCoordinator coordinator;
    private final Duration grace;
    private final Duration readyTtl;
    private final long intervalSeconds;
    private final String instanceId = UUID.randomUUID().toString();
    private ScheduledExecutorService scheduler;

    LiveReaper(BroadcastService broadcasts, RedisLiveCoordinator coordinator,
               @Value("${app.live.broadcaster-grace-seconds:15}") long graceSeconds,
               @Value("${app.live.ready-ttl-seconds:3600}") long readyTtlSeconds,
               @Value("${app.live.reaper-interval-seconds:5}") long intervalSeconds) {
        this.broadcasts = broadcasts;
        this.coordinator = coordinator;
        this.grace = Duration.ofSeconds(graceSeconds);
        this.readyTtl = Duration.ofSeconds(readyTtlSeconds);
        this.intervalSeconds = intervalSeconds;
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "live-reaper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safeReap, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void safeReap() {
        try {
            reap();
        } catch (RuntimeException e) {
            log.warn("Live reaper cycle failed", e);
        }
    }

    /** One locked reaper cycle; returns how many broadcasts it ended. */
    int reap() {
        // The lock only spreads the work: it expires before the next cycle even if this instance dies holding it.
        if (!coordinator.tryLock("live-reaper", Duration.ofSeconds(Math.max(1, intervalSeconds - 1)), instanceId)) {
            return 0;
        }
        return reapUnlocked();
    }

    /** The scan itself, without the lock. Package-private for tests. */
    int reapUnlocked() {
        int ended = 0;
        for (Broadcast b : broadcasts.findActiveLive()) {
            boolean orphaned;
            if (b.getStatus() == BroadcastStatus.LIVE) {
                orphaned = coordinator.absentLongerThan(b.getId(), grace);
            } else { // READY
                orphaned = !coordinator.broadcasterPresent(b.getId())
                        && b.getCreatedAt().plus(readyTtl).isBefore(Instant.now());
            }
            if (orphaned && broadcasts.endIfStillActive(b.getId())) {
                ended++;
                log.info("Ended live broadcast {} (no broadcaster presence beyond the grace period)", b.getId());
            }
        }
        return ended;
    }
}
