package com.chzzk.broadcast;

/** Published after a live broadcast changes to ENDED, so the signaling layer can notify and disconnect peers. */
public record BroadcastEndedEvent(long broadcastId) {
}
