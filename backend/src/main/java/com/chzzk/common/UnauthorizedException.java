package com.chzzk.common;

/** The caller is not authenticated (or the credentials are wrong): HTTP 401. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
