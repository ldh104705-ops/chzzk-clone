package com.chzzk.broadcast;

import com.chzzk.auth.CurrentUser;
import java.net.URI;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Who may call what (enforced by SecurityConfig for "must be logged in" and by BroadcastService for "must be the
 * owner"):
 * <ul>
 *   <li>public: list, detail, video stream</li>
 *   <li>logged in: create recorded / live broadcast (the caller becomes the owner)</li>
 *   <li>owner only: rename, delete, end</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/broadcasts")
public class BroadcastController {

    private final BroadcastService service;

    public BroadcastController(BroadcastService service) {
        this.service = service;
    }

    @PostMapping(path = "/recorded", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BroadcastResponse> registerRecorded(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String videoUrl) {
        Broadcast created = service.registerRecorded(CurrentUser.id(jwt), title, file, videoUrl);
        return ResponseEntity.created(URI.create("/api/broadcasts/" + created.getId()))
                .body(BroadcastResponse.from(created));
    }

    public record CreateLiveRequest(String title) {
    }

    public record UpdateBroadcastRequest(String title) {
    }

    /** Creates a live broadcast (status READY) owned by the caller; only the owner can stream to it. */
    @PostMapping("/live")
    public ResponseEntity<BroadcastResponse> createLive(@AuthenticationPrincipal Jwt jwt,
                                                        @RequestBody CreateLiveRequest request) {
        Broadcast b = service.createLive(CurrentUser.id(jwt), request.title());
        return ResponseEntity.created(URI.create("/api/broadcasts/" + b.getId())).body(BroadcastResponse.from(b));
    }

    /** Ends a live broadcast. Owner only (403 for anyone else). */
    @PostMapping("/{id}/end")
    public BroadcastResponse end(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return BroadcastResponse.from(service.endLive(id, CurrentUser.id(jwt)));
    }

    @PatchMapping("/{id}")
    public BroadcastResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                    @RequestBody UpdateBroadcastRequest request) {
        return BroadcastResponse.from(service.rename(id, CurrentUser.id(jwt), request.title()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(id, CurrentUser.id(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<BroadcastResponse> list(@RequestParam(required = false) BroadcastType type,
                                        @RequestParam(required = false) BroadcastStatus status) {
        return service.list(type, status).stream().map(BroadcastResponse::from).toList();
    }

    @GetMapping("/{id}")
    public BroadcastResponse get(@PathVariable long id) {
        return BroadcastResponse.from(service.get(id));
    }

    /** Streams the stored file. Returning a Resource lets Spring MVC answer Range requests (206). */
    @GetMapping("/{id}/video")
    public ResponseEntity<Resource> video(@PathVariable long id) {
        Broadcast b = service.get(id);
        Resource video = service.loadVideo(b);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(b.getContentType()))
                .cacheControl(CacheControl.noCache())
                .body(video);
    }
}
