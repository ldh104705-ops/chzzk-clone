package com.chzzk.auth;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local-mode denylist: kept in this process only (lost on restart, not shared between instances). Used when
 * {@code app.cluster.mode=local}; cluster mode uses {@link RedisTokenDenylist} instead.
 */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "local", matchIfMissing = true)
public class InMemoryTokenDenylist implements TokenDenylist {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    @Override
    public void revoke(String tokenId, Instant expiresAt) {
        Instant now = Instant.now();
        revoked.values().removeIf(exp -> !exp.isAfter(now)); // drop entries whose token is expired anyway
        if (tokenId != null && expiresAt != null && expiresAt.isAfter(now)) {
            revoked.put(tokenId, expiresAt);
        }
    }

    @Override
    public boolean isRevoked(String tokenId) {
        return tokenId != null && revoked.containsKey(tokenId);
    }
}
