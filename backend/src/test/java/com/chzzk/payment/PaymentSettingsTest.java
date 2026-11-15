package com.chzzk.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chzzk.common.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

/** Which key combinations switch payments on. The values are placeholders, never real keys. */
class PaymentSettingsTest {

    private static PaymentSettings settings(String client, String secret, boolean allowLive) {
        return new PaymentSettings(client, secret, "https://api.example.test/", false, allowLive, "http://localhost:5173/");
    }

    @Test
    void testKeysEnablePayments() {
        PaymentSettings s = settings("test_gck_x", "test_gsk_x", false);
        assertThat(s.enabled()).isTrue();
        s.requireEnabled(); // does not throw
        assertThat(s.apiBaseUrl()).isEqualTo("https://api.example.test"); // trailing slash removed
        assertThat(s.frontendBaseUrl()).isEqualTo("http://localhost:5173");
    }

    @Test
    void missingKeysDisablePaymentsAndRequireEnabledThrows503() {
        for (PaymentSettings s : new PaymentSettings[] {
                settings("", "", false), settings(null, null, false), settings("test_gck_x", "", false), settings("", "test_gsk_x", false)}) {
            assertThat(s.enabled()).isFalse();
            assertThatThrownBy(s::requireEnabled).isInstanceOf(ServiceUnavailableException.class)
                    .hasMessage("결제 설정이 없어 후원을 사용할 수 없습니다.");
        }
    }

    @Test
    void liveKeysAreRefusedUnlessExplicitlyAllowed() {
        assertThat(settings("live_gck_x", "live_gsk_x", false).enabled()).isFalse();
        assertThat(settings("test_gck_x", "live_gsk_x", false).enabled()).isFalse(); // mixed sets are refused too
        assertThat(settings("live_gck_x", "test_gsk_x", false).enabled()).isFalse();
        assertThat(settings("live_gck_x", "live_gsk_x", true).enabled()).isTrue(); // TOSS_ALLOW_LIVE_KEYS=true
    }
}
