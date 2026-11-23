package com.chzzk.auth;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cluster-mode denylist in Redis: key {@code auth:denylist:{jti}} whose TTL equals the remaining life of the token,
 * so Redis forgets it exactly when the token would have expired anyway. Shared by every backend instance.
 */
@Component
@ConditionalOnProperty(name = "app.cluster.mode", havingValue = "redis")
public class RedisTokenDenylist implements TokenDenylist {

    static final String KEY_PREFIX = "auth:denylist:";

    private final StringRedisTemplate redis;

    public RedisTokenDenylist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void revoke(String tokenId, Instant expiresAt) {
        if (tokenId == null || expiresAt == null) {
            return;
        }
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        if (remaining.isNegative() || remaining.isZero()) {
            return; // already expired: nothing to remember
        }
        redis.opsForValue().set(KEY_PREFIX + tokenId, "1", remaining);
    }

    @Override
    public boolean isRevoked(String tokenId) {
        return tokenId != null && Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + tokenId));
    }
}
