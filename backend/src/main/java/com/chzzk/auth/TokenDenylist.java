package com.chzzk.auth;

import java.time.Instant;

/**
 * Ids (JWT {@code jti}) of tokens that were logged out before they expired. Entries disappear once the token
 * would have expired anyway. Implementations: {@link InMemoryTokenDenylist} (local mode, single instance) and
 * {@link RedisTokenDenylist} (cluster mode: every instance sees a logout done on any other instance).
 */
public interface TokenDenylist {

    void revoke(String tokenId, Instant expiresAt);

    boolean isRevoked(String tokenId);
}
