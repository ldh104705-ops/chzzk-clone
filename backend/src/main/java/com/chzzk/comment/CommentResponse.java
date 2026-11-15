package com.chzzk.comment;

import java.time.Instant;

/** {@code authorId} lets the client show edit/delete only on the logged-in user's own comments (the server enforces it too). */
public record CommentResponse(
        Long id,
        Long broadcastId,
        String content,
        Long authorId,
        String authorNickname,
        Instant createdAt,
        Instant updatedAt) {

    public static CommentResponse from(Comment c) {
        return new CommentResponse(c.getId(), c.getBroadcastId(), c.getContent(), c.getAuthor().getId(),
                c.getAuthor().getNickname(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
