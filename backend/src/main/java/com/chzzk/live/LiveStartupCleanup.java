package com.chzzk.live;

import com.chzzk.broadcast.BroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Signaling rooms live in memory, so after a restart no broadcaster can still be connected: broadcasts left
 * READY/LIVE by the previous process are ended instead of showing up as "방송 중" forever.
 *
 * <p>Local mode only. With several instances (cluster mode) a new pod starting must NOT end broadcasts that are
 * streaming through the other pods, so {@link LiveReaper} replaces this class there.
 */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "local", matchIfMissing = true)
class LiveStartupCleanup {

    private static final Logger log = LoggerFactory.getLogger(LiveStartupCleanup.class);

    private final BroadcastService broadcasts;

    LiveStartupCleanup(BroadcastService broadcasts) {
        this.broadcasts = broadcasts;
    }

    @EventListener(ApplicationReadyEvent.class)
    void endOrphanedBroadcasts() {
        int ended = broadcasts.endOrphanedLive();
        if (ended > 0) {
            log.info("Ended {} live broadcast(s) left over from a previous run", ended);
        }
    }
}
