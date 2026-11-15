package com.chzzk.live;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * In-memory signaling state of one live broadcast. All access happens while holding the room's monitor
 * ({@code synchronized (room)}); the durable state (LIVE/ENDED) lives in the database.
 */
final class LiveRoom {

    Peer broadcaster;
    final Map<String, Peer> viewers = new LinkedHashMap<>();
    /** Pending "end the broadcast" task while the broadcaster is disconnected (grace period). */
    ScheduledFuture<?> graceTask;
    boolean closed;

    void cancelGrace() {
        if (graceTask != null) {
            graceTask.cancel(false);
            graceTask = null;
        }
    }
}
