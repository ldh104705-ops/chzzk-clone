package com.chzzk.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Symmetric (HS256) JWT signing. The secret comes from APP_JWT_SECRET (at least 32 bytes). If it is not set a
 * random secret is generated at startup so the server still starts, but every restart invalidates all tokens.
 * In cluster mode ({@code app.cluster.mode=redis}) the secret is mandatory and startup fails without it.
 */
@Configuration
public class JwtConfig {

    static final int MIN_SECRET_BYTES = 32;
    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    @Bean
    SecretKey jwtSecretKey(@Value("${app.jwt.secret:}") String configured,
                         @Value("${app.cluster.mode:local}") String clusterMode) {
        byte[] bytes;
        if (configured == null || configured.isBlank()) {
            if ("redis".equalsIgnoreCase(clusterMode)) {
                // Every instance must verify tokens issued by any other instance, so they must share one secret.
                throw new IllegalStateException("APP_JWT_SECRET must be set (32+ bytes) when APP_CLUSTER_MODE=redis: "
                        + "a per-instance random secret would make logins fail on every other instance. "
                        + "Generate one with: openssl rand -base64 48");
            }
            bytes = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(bytes);
            log.warn("APP_JWT_SECRET is not set: generated a random JWT secret for this run. "
                    + "All login tokens become invalid when the server restarts. Set APP_JWT_SECRET (32+ bytes) for stable logins.");
        } else {
            bytes = configured.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("APP_JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes long");
            }
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    /** Verifies signature and expiry, and rejects tokens that were logged out (see {@link TokenDenylist}). */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, TokenDenylist denylist) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> notLoggedOut = jwt -> denylist.isRevoked(jwt.getId())
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "로그아웃된 토큰입니다.", null))
                : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), notLoggedOut));
        return decoder;
    }

    @Bean
    Duration jwtTtl(@Value("${app.jwt.ttl:PT12H}") Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("APP_JWT_TTL must be a positive ISO-8601 duration such as PT12H");
        }
        return ttl;
    }
}
