package com.chzzk.comment;

import com.chzzk.auth.CurrentUser;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading comments is public; writing needs a login (SecurityConfig, 401); editing/deleting needs to be the
 * comment author (CommentService, 403).
 */
@RestController
public class CommentController {

    public record CommentRequest(String content) {
    }

    private final CommentService service;

    public CommentController(CommentService service) {
        this.service = service;
    }

    @GetMapping("/api/broadcasts/{broadcastId}/comments")
    public CommentPageResponse list(@PathVariable long broadcastId,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return service.list(broadcastId, page, size);
    }

    @PostMapping("/api/broadcasts/{broadcastId}/comments")
    public ResponseEntity<CommentResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable long broadcastId,
                                                  @RequestBody CommentRequest request) {
        Comment created = service.create(CurrentUser.id(jwt), broadcastId, request.content());
        return ResponseEntity.created(URI.create("/api/comments/" + created.getId()))
                .body(CommentResponse.from(created));
    }

    @PatchMapping("/api/comments/{id}")
    public CommentResponse edit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                @RequestBody CommentRequest request) {
        return CommentResponse.from(service.edit(id, CurrentUser.id(jwt), request.content()));
    }

    @DeleteMapping("/api/comments/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(id, CurrentUser.id(jwt));
        return ResponseEntity.noContent().build();
    }
}
