package com.chzzk.auth;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Ids (JWT {@code jti}) of tokens that were logged out before they expired. Entries disappear once the token
 * would have expired anyway, so the map stays small. In memory only: it is lost on restart and not shared
 * between instances (acceptable for this single-instance assignment; a shared store such as Redis would be
 * the production answer).
 */
@Component
public class TokenDenylist {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    public void revoke(String tokenId, Instant expiresAt) {
        Instant now = Instant.now();
        revoked.values().removeIf(exp -> !exp.isAfter(now)); // drop entries whose token is expired anyway
        if (tokenId != null && expiresAt != null && expiresAt.isAfter(now)) {
            revoked.put(tokenId, expiresAt);
        }
    }

    public boolean isRevoked(String tokenId) {
        return tokenId != null && revoked.containsKey(tokenId);
    }
}
