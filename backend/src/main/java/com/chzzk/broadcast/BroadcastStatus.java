package com.chzzk.broadcast;

/**
 * Lifecycle state. A recorded broadcast is always ENDED. A live broadcast is READY after creation,
 * LIVE once the broadcaster's phone connects to the signaling socket, and ENDED when it stops.
 */
public enum BroadcastStatus {
    READY,
    LIVE,
    ENDED
}
