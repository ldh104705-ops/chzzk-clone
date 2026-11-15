package com.chzzk.broadcast;

/**
 * Returned once, when a live broadcast is created. {@code broadcasterKey} is shown only here
 * (the server keeps just its hash) and must be sent on the signaling socket and to the end endpoint.
 */
public record LiveBroadcastResponse(BroadcastResponse broadcast, String broadcasterKey) {
}
