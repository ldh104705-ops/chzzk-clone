package com.chzzk.live;

import java.util.List;
import java.util.function.Consumer;

/**
 * The part of live signaling that must be shared when several backend instances run: who is broadcasting, who is
 * watching, and how a message reaches a peer whose WebSocket is connected to ANOTHER instance.
 *
 * <p>Each instance keeps only its own sockets ({@link LiveRoom}). Everything addressed to a peer goes through
 * {@link #send}; every instance receives every envelope and delivers it to the sockets it holds locally.
 * {@link LocalLiveCoordinator} (default) is in memory and delivers immediately; {@link RedisLiveCoordinator}
 * (cluster mode) keeps presence in Redis and relays envelopes with Redis pub/sub.
 */
interface LiveCoordinator {

    /** Target value: every peer of the broadcast (used for {@code broadcast-ended}). */
    String ALL = "*";
    /** Target value: the broadcaster of the broadcast. */
    String BROADCASTER = "broadcaster";
    /** Target value: close a local broadcaster socket unless its token equals {@code exceptToken} (a newer connection took over). */
    String REPLACE = "!replace";

    /** A message for one peer (by peer id), the broadcaster, or everybody. {@code payload} is the JSON text to send. */
    record Envelope(long broadcastId, String to, String payload, String exceptToken) {
    }

    /** Registers the callback that delivers an envelope to the sockets connected to THIS instance. */
    void subscribe(Consumer<Envelope> localDelivery);

    /** Sends to the target wherever it is connected (possibly on another instance, possibly also to this one). */
    void send(Envelope envelope);

    // ---- broadcaster presence (token identifies the broadcaster's socket) ----------------------------

    void broadcasterJoined(long broadcastId, String token);

    /** Called periodically while the broadcaster socket is open on this instance; keeps the presence alive. */
    void broadcasterHeartbeat(long broadcastId, String token);

    /** Removes the presence, but only if it still belongs to {@code token} (a newer connection may have replaced it). */
    void broadcasterLeft(long broadcastId, String token);

    boolean broadcasterPresent(long broadcastId);

    // ---- viewers -------------------------------------------------------------------------------------

    void viewerJoined(long broadcastId, String viewerId);

    void viewerHeartbeat(long broadcastId, String viewerId);

    void viewerLeft(long broadcastId, String viewerId);

    /** Ids of the viewers currently connected, on any instance, in join order. */
    List<String> viewers(long broadcastId);

    boolean hasViewer(long broadcastId, String viewerId);

    /** Forgets all shared state of an ended broadcast. */
    void clear(long broadcastId);
}
