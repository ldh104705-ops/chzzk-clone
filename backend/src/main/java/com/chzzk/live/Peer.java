package com.chzzk.live;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** One connected WebSocket (broadcaster or viewer). Sends are serialized: a session is not thread-safe. */
final class Peer {

    enum Role { BROADCASTER, VIEWER }

    private static final Logger log = LoggerFactory.getLogger(Peer.class);

    private final WebSocketSession session;
    private volatile Role role;
    private volatile long broadcastId;
    private volatile String peerId;

    Peer(WebSocketSession session) {
        this.session = session;
    }

    WebSocketSession session() { return session; }
    Role role() { return role; }
    long broadcastId() { return broadcastId; }
    String peerId() { return peerId; }
    boolean joined() { return role != null; }

    void assign(Role role, long broadcastId, String peerId) {
        this.broadcastId = broadcastId;
        this.peerId = peerId;
        this.role = role;
    }

    void send(String json) {
        synchronized (session) {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(new TextMessage(json));
            } catch (IOException | RuntimeException e) {
                log.debug("Could not send to session {}: {}", session.getId(), e.toString());
            }
        }
    }

    void close(CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not close session {}: {}", session.getId(), e.toString());
        }
    }
}
