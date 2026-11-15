package com.chzzk.comment;

import com.chzzk.auth.User;
import com.chzzk.auth.UserRepository;
import com.chzzk.broadcast.BroadcastService;
import com.chzzk.common.ForbiddenException;
import com.chzzk.common.InvalidRequestException;
import com.chzzk.common.NotFoundException;
import com.chzzk.common.UnauthorizedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentService {

    static final int MAX_CONTENT_LENGTH = 500;
    static final int DEFAULT_PAGE_SIZE = 30;
    static final int MAX_PAGE_SIZE = 100;

    private final CommentRepository comments;
    private final UserRepository users;
    private final BroadcastService broadcasts;

    public CommentService(CommentRepository comments, UserRepository users, BroadcastService broadcasts) {
        this.comments = comments;
        this.users = users;
        this.broadcasts = broadcasts;
    }

    @Transactional(readOnly = true)
    public CommentPageResponse list(long broadcastId, Integer page, Integer size) {
        broadcasts.get(broadcastId); // 404 for an unknown broadcast
        int p = page == null ? 0 : Math.max(page, 0);
        int s = size == null ? DEFAULT_PAGE_SIZE : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<Comment> found = comments.findByBroadcastIdOrderByCreatedAtDescIdDesc(broadcastId, PageRequest.of(p, s));
        return new CommentPageResponse(found.getContent().stream().map(CommentResponse::from).toList(),
                p, s, found.getTotalElements(), found.hasNext());
    }

    /** The author is always the logged-in user; the request cannot name one. */
    @Transactional
    public Comment create(long userId, long broadcastId, String content) {
        broadcasts.get(broadcastId);
        String clean = validateContent(content);
        User author = users.findById(userId).orElseThrow(() -> new UnauthorizedException("존재하지 않는 사용자입니다."));
        return comments.save(new Comment(broadcastId, author, clean));
    }

    /** Author only (403 for anyone else, including the broadcast owner). */
    @Transactional
    public Comment edit(long commentId, long userId, String content) {
        Comment c = find(commentId);
        requireAuthor(c, userId);
        c.edit(validateContent(content));
        return comments.save(c);
    }

    @Transactional
    public void delete(long commentId, long userId) {
        Comment c = find(commentId);
        requireAuthor(c, userId);
        comments.delete(c);
    }

    private Comment find(long id) {
        return comments.findById(id).orElseThrow(() -> new NotFoundException("댓글을 찾을 수 없습니다: " + id));
    }

    private static void requireAuthor(Comment c, long userId) {
        if (!c.getAuthor().getId().equals(userId)) {
            throw new ForbiddenException("댓글 작성자만 할 수 있습니다.");
        }
    }

    static String validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new InvalidRequestException("댓글 내용은 비어 있을 수 없습니다.");
        }
        String clean = content.trim();
        if (clean.codePointCount(0, clean.length()) > MAX_CONTENT_LENGTH) {
            throw new InvalidRequestException("댓글은 " + MAX_CONTENT_LENGTH + "자 이하여야 합니다.");
        }
        return clean;
    }
}
