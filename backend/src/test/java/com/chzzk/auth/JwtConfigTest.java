package com.chzzk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Startup behaviour of the JWT secret/TTL settings (no Spring context needed). */
class JwtConfigTest {

    private final JwtConfig config = new JwtConfig();

    @Test
    void generatesARandomSecretWhenNoneIsConfigured() {
        var a = config.jwtSecretKey("");
        var b = config.jwtSecretKey("   ");
        assertThat(a.getEncoded()).hasSize(JwtConfig.MIN_SECRET_BYTES);
        assertThat(a.getEncoded()).isNotEqualTo(b.getEncoded());
    }

    @Test
    void usesAConfiguredSecretAndRejectsAShortOne() {
        String secret = "0123456789abcdef0123456789abcdef";
        assertThat(config.jwtSecretKey(secret).getEncoded()).isEqualTo(secret.getBytes());
        assertThatThrownBy(() -> config.jwtSecretKey("too-short")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ttlMustBePositive() {
        assertThat(config.jwtTtl(Duration.ofHours(1))).isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> config.jwtTtl(Duration.ZERO)).isInstanceOf(IllegalStateException.class);
    }
}
