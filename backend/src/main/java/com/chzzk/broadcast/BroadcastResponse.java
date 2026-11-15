package com.chzzk.broadcast;

import java.time.Instant;

/**
 * API representation of a broadcast.
 * {@code playbackUrl} is what the player uses: for uploaded files it is this server's
 * {@code /api/broadcasts/{id}/video} path, for external-URL broadcasts it is that URL itself.
 */
public record BroadcastResponse(
        Long id,
        String title,
        BroadcastType type,
        BroadcastStatus status,
        String sourceType,
        String playbackUrl,
        String originalFileName,
        String contentType,
        Long sizeBytes,
        Instant createdAt) {

    public static BroadcastResponse from(Broadcast b) {
        boolean file = b.isStoredFile();
        return new BroadcastResponse(
                b.getId(),
                b.getTitle(),
                b.getType(),
                b.getStatus(),
                file ? "FILE" : "URL",
                file ? "/api/broadcasts/" + b.getId() + "/video" : b.getVideoUrl(),
                b.getOriginalFileName(),
                b.getContentType(),
                b.getSizeBytes(),
                b.getCreatedAt());
    }
}
