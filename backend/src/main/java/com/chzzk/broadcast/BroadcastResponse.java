package com.chzzk.broadcast;

import java.time.Instant;

/**
 * API representation of a broadcast.
 * For recordings {@code playbackUrl} is what the player uses: for uploaded files it is this server's
 * {@code /api/broadcasts/{id}/video} path, for external-URL broadcasts it is that URL itself.
 * {@code ownerId} lets the client decide whether to show edit/delete buttons (the server enforces it anyway).
 * Live broadcasts have {@code sourceType} "LIVE" and no playback URL (viewers use WebRTC signaling).
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
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        Long ownerId,
        String ownerNickname) {

    public static BroadcastResponse from(Broadcast b) {
        if (b.isLiveType()) {
            return new BroadcastResponse(b.getId(), b.getTitle(), b.getType(), b.getStatus(), "LIVE", null,
                    null, null, null, b.getCreatedAt(), b.getStartedAt(), b.getEndedAt(), b.getOwnerId(), nickname(b));
        }
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
                b.getCreatedAt(),
                b.getStartedAt(),
                b.getEndedAt(),
                b.getOwnerId(),
                nickname(b));
    }

    private static String nickname(Broadcast b) {
        return b.getOwner() == null ? null : b.getOwner().getNickname();
    }
}
