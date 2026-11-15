package com.chzzk.broadcast;

import java.net.URI;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Steps 1-2: NO user authentication or authorization. Every endpoint is public; there is deliberately
 * no security dependency yet (login, ownership and permission checks arrive in step 3). The only
 * secret is the per-broadcast broadcaster key, which just stops a stranger from hijacking a stream.
 */
@RestController
@RequestMapping("/api/broadcasts")
public class BroadcastController {

    public static final String BROADCASTER_KEY_HEADER = "X-Broadcaster-Key";

    private final BroadcastService service;

    public BroadcastController(BroadcastService service) {
        this.service = service;
    }

    @PostMapping(path = "/recorded", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BroadcastResponse> registerRecorded(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String videoUrl) {
        Broadcast created = service.registerRecorded(title, file, videoUrl);
        return ResponseEntity.created(URI.create("/api/broadcasts/" + created.getId()))
                .body(BroadcastResponse.from(created));
    }

    public record CreateLiveRequest(String title) {
    }

    /** Creates a live broadcast (status READY) and returns the one-time broadcaster key. */
    @PostMapping("/live")
    public ResponseEntity<LiveBroadcastResponse> createLive(@RequestBody CreateLiveRequest request) {
        BroadcastService.CreatedLive created = service.createLive(request.title());
        Broadcast b = created.broadcast();
        return ResponseEntity.created(URI.create("/api/broadcasts/" + b.getId()))
                .body(new LiveBroadcastResponse(BroadcastResponse.from(b), created.broadcasterKey()));
    }

    /** Ends a live broadcast. Requires the broadcaster key from creation (403 otherwise). */
    @PostMapping("/{id}/end")
    public BroadcastResponse end(@PathVariable long id,
                                 @RequestHeader(name = BROADCASTER_KEY_HEADER, required = false) String broadcasterKey) {
        return BroadcastResponse.from(service.endLive(id, broadcasterKey));
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
