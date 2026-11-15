package com.chzzk.common;

/** An upstream service (the payment provider) failed or answered something we cannot trust: HTTP 502. */
public class BadGatewayException extends RuntimeException {
    public BadGatewayException(String message) {
        super(message);
    }
}
