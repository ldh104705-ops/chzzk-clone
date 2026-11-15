package com.chzzk.storage;

public class VideoNotFoundException extends RuntimeException {
    public VideoNotFoundException(String message) {
        super(message);
    }
}
