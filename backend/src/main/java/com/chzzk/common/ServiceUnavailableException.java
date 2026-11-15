package com.chzzk.common;

/** A feature is switched off by configuration (e.g. payments without Toss keys): HTTP 503. */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
