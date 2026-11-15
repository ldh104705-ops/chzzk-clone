package com.chzzk.payment;

import com.chzzk.common.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Toss Payments configuration, read from the environment only (see application.properties). Payments are enabled
 * only when both keys are present AND are TEST keys (they start with {@code test_}); this assignment never touches
 * real money, so a live key is refused unless {@code TOSS_ALLOW_LIVE_KEYS=true} is set on purpose. A disabled
 * configuration never stops the server from starting: the donation endpoints answer 503 instead.
 * The secret key is never logged and never leaves the server (only the client key goes to the browser).
 */
@Component
public class PaymentSettings {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettings.class);

    public static final long MIN_AMOUNT = 1_000;
    public static final long MAX_AMOUNT = 1_000_000;
    public static final int MAX_MESSAGE_LENGTH = 100;

    private final String clientKey;
    private final String secretKey;
    private final String apiBaseUrl;
    private final boolean webhookEnabled;
    private final String frontendBaseUrl;
    private final boolean enabled;

    public PaymentSettings(@Value("${app.toss.client-key:}") String clientKey,
                           @Value("${app.toss.secret-key:}") String secretKey,
                           @Value("${app.toss.api-base-url:https://api.tosspayments.com}") String apiBaseUrl,
                           @Value("${app.toss.webhook-enabled:false}") boolean webhookEnabled,
                           @Value("${app.toss.allow-live-keys:false}") boolean allowLiveKeys,
                           @Value("${app.frontend.base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.clientKey = clientKey == null ? "" : clientKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.apiBaseUrl = stripTrailingSlashes(apiBaseUrl);
        this.webhookEnabled = webhookEnabled;
        this.frontendBaseUrl = stripTrailingSlashes(frontendBaseUrl);

        if (this.clientKey.isEmpty() || this.secretKey.isEmpty()) {
            log.warn("TOSS_CLIENT_KEY / TOSS_SECRET_KEY are not set: donations are DISABLED (the rest of the app works).");
            this.enabled = false;
        } else if (!allowLiveKeys && !(this.clientKey.startsWith("test_") && this.secretKey.startsWith("test_"))) {
            log.warn("Toss keys do not start with 'test_': donations are DISABLED. This project is test-mode only "
                    + "(set TOSS_ALLOW_LIVE_KEYS=true only if you really mean it).");
            this.enabled = false;
        } else {
            this.enabled = true;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** Throws 503 (Korean message) when payments are not configured. */
    public void requireEnabled() {
        if (!enabled) {
            throw new ServiceUnavailableException("결제 설정이 없어 후원을 사용할 수 없습니다.");
        }
    }

    /** Public by design: the browser needs it to open the Toss payment window. */
    public String clientKey() {
        return clientKey;
    }

    /** Server-side only. Never log it, never return it from an endpoint. */
    String secretKey() {
        return secretKey;
    }

    public String apiBaseUrl() {
        return apiBaseUrl;
    }

    public boolean webhookEnabled() {
        return webhookEnabled;
    }

    public String frontendBaseUrl() {
        return frontendBaseUrl;
    }

    private static String stripTrailingSlashes(String value) {
        String v = value == null ? "" : value.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
