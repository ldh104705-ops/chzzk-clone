package com.chzzk.payment;

/**
 * A failed call to Toss. {@code httpStatus} is 0 when no answer arrived at all (timeout, connection error).
 * {@link #isRetryable()} separates "Toss says this payment is invalid" (definitive: mark the donation FAILED) from
 * "we could not get a trustworthy answer" (keep it PENDING so the same approval can be retried safely).
 */
public class TossApiException extends RuntimeException {

    private final int httpStatus;
    private final String code;
    private final String tossMessage;

    public TossApiException(int httpStatus, String code, String tossMessage) {
        super("Toss API error: http=" + httpStatus + " code=" + code);
        this.httpStatus = httpStatus;
        this.code = code;
        this.tossMessage = tossMessage;
    }

    public TossApiException(String description, Throwable cause) {
        super("Toss API unreachable: " + description, cause);
        this.httpStatus = 0;
        this.code = "NETWORK_ERROR";
        this.tossMessage = "결제 서비스에 연결하지 못했습니다.";
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String tossMessage() {
        return tossMessage;
    }

    /** Network failure, Toss 5xx, rate limit, or a key/permission problem (a server-side configuration error). */
    public boolean isRetryable() {
        return httpStatus == 0 || httpStatus >= 500 || httpStatus == 429 || httpStatus == 401 || httpStatus == 403;
    }
}
