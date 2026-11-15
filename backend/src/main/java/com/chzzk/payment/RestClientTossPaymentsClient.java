package com.chzzk.payment;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real Toss client. Authentication is HTTP Basic with base64("secretKey:") (note the trailing colon: the password
 * is empty). Connect/read timeouts keep a slow Toss from hanging request threads. The Authorization header and
 * response bodies are never logged.
 */
@Component
public class RestClientTossPaymentsClient implements TossPaymentsClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientTossPaymentsClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final PaymentSettings settings;
    private final ObjectMapper mapper;
    private final RestClient http;

    public RestClientTossPaymentsClient(PaymentSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public TossPayment confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        return call(() -> http.post()
                .uri(URI.create(settings.apiBaseUrl() + "/v1/payments/confirm"))
                .header(HttpHeaders.AUTHORIZATION, basicAuth())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .exchange(this::read));
    }

    @Override
    public TossPayment get(String paymentKey) {
        return call(() -> http.get()
                .uri(URI.create(settings.apiBaseUrl() + "/v1/payments/" + encodePathSegment(paymentKey)))
                .header(HttpHeaders.AUTHORIZATION, basicAuth())
                .exchange(this::read));
    }

    private TossPayment call(Supplier<TossPayment> request) {
        try {
            return request.get();
        } catch (TossApiException e) {
            log.warn("Toss API call failed: http={} code={}", e.httpStatus(), e.code());
            throw e;
        } catch (RestClientException | IllegalArgumentException e) { // I/O errors, timeouts, bad URL
            log.warn("Toss API call could not complete: {}", e.getClass().getSimpleName());
            throw new TossApiException(e.getClass().getSimpleName(), e);
        }
    }

    /** Turns a Toss response into a TossPayment, or throws TossApiException carrying Toss's error code/message. */
    private TossPayment read(HttpRequest request, RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        int status = response.getStatusCode().value();
        byte[] bytes = response.getBody().readAllBytes();
        JsonNode body;
        try {
            body = bytes.length == 0 ? null : mapper.readTree(bytes);
        } catch (RuntimeException e) {
            body = null;
        }
        if (status < 200 || status >= 300) {
            String code = text(body, "code");
            String message = text(body, "message");
            throw new TossApiException(status, code == null ? "UNKNOWN" : code,
                    message == null ? "결제 승인에 실패했습니다." : message);
        }
        if (body == null || text(body, "paymentKey") == null || text(body, "orderId") == null
                || !body.hasNonNull("totalAmount")) {
            throw new TossApiException(502, "INVALID_RESPONSE", "결제 서비스 응답을 해석할 수 없습니다.");
        }
        String approvedAt = text(body, "approvedAt");
        return new TossPayment(text(body, "paymentKey"), text(body, "orderId"), text(body, "status"),
                body.get("totalAmount").asLong(), approvedAt == null ? null : OffsetDateTime.parse(approvedAt));
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((settings.secretKey() + ":").getBytes(StandardCharsets.UTF_8));
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
