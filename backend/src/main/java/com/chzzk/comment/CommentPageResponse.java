package com.chzzk.comment;

import java.util.List;

/** One page of comments, newest first. */
public record CommentPageResponse(List<CommentResponse> items, int page, int size, long totalElements, boolean hasNext) {
}
