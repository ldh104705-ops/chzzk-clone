package com.chzzk.storage;

/** Result of storing a video: the generated name to persist, plus upload facts. */
public record StoredVideo(String storedName, String originalName, String contentType, long sizeBytes) {
}
