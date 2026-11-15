package com.chzzk.payment;

import com.chzzk.common.NotFoundException;
import com.chzzk.donation.DonationService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Public payment endpoints: the browser's config and Toss's webhook. */
@RestController
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentSettings settings;
    private final DonationService donations;

    public PaymentController(PaymentSettings settings, DonationService donations) {
        this.settings = settings;
        this.donations = donations;
    }

    /**
     * {"enabled": false} without keys. With keys: the CLIENT key (public by design; the secret key never leaves the
     * server) and the donation limits, so the UI and the server share one set of rules.
     */
    @GetMapping("/api/payments/config")
    public Map<String, Object> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", settings.enabled());
        if (settings.enabled()) {
            body.put("clientKey", settings.clientKey());
            body.put("minAmount", PaymentSettings.MIN_AMOUNT);
            body.put("maxAmount", PaymentSettings.MAX_AMOUNT);
            body.put("maxMessageLength", PaymentSettings.MAX_MESSAGE_LENGTH);
        }
        return body;
    }

    /**
     * Toss webhook (PAYMENT_STATUS_CHANGED). Public and off unless TOSS_WEBHOOK_ENABLED=true. The body is NOT
     * trusted: only data.paymentKey is read, and the payment is re-fetched from Toss before anything is changed.
     * Always 200 for a well-formed notification (even when nothing needed doing) so Toss does not retry endlessly.
     */
    @PostMapping("/api/payments/toss/webhook")
    public Map<String, Object> webhook(@RequestBody JsonNode body) {
        if (!settings.webhookEnabled()) {
            throw new NotFoundException("웹훅이 비활성화되어 있습니다.");
        }
        JsonNode data = body == null ? null : body.get("data");
        String paymentKey = data != null && data.hasNonNull("paymentKey") ? data.get("paymentKey").asString() : null;
        if (paymentKey == null) {
            log.info("Webhook without data.paymentKey ignored");
            return Map.of("received", true, "processed", false);
        }
        donations.handleWebhook(paymentKey);
        return Map.of("received", true, "processed", true);
    }
}
