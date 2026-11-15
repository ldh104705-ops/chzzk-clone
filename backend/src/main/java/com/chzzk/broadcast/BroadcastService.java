package com.chzzk.broadcast;

import com.chzzk.common.ForbiddenException;
import com.chzzk.common.InvalidRequestException;
import com.chzzk.common.NotFoundException;
import com.chzzk.storage.StoredVideo;
import com.chzzk.storage.VideoNotFoundException;
import com.chzzk.storage.VideoStorage;
import jakarta.persistence.criteria.Predicate;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BroadcastService {

    static final int MAX_TITLE_LENGTH = 200;
    static final int MAX_URL_LENGTH = 2000;

    private final BroadcastRepository repository;
    private final VideoStorage storage;
    private final ApplicationEventPublisher events;

    public BroadcastService(BroadcastRepository repository, VideoStorage storage, ApplicationEventPublisher events) {
        this.repository = repository;
        this.storage = storage;
        this.events = events;
    }

    /** Registers a recorded broadcast from exactly one of: an uploaded file or an external URL. */
    public Broadcast registerRecorded(String title, MultipartFile file, String videoUrl) {
        String cleanTitle = validateTitle(title);
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasUrl = videoUrl != null && !videoUrl.isBlank();
        if (hasFile == hasUrl) {
            throw new InvalidRequestException("영상 파일 또는 영상 URL 중 하나만 입력해야 합니다.");
        }
        if (hasUrl) {
            return repository.save(Broadcast.recordedFromUrl(cleanTitle, validateUrl(videoUrl.trim())));
        }
        return registerFile(cleanTitle, file);
    }

    private Broadcast registerFile(String title, MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("video/")) {
            throw new InvalidRequestException("video/* 형식의 영상 파일만 업로드할 수 있습니다.");
        }
        StoredVideo stored;
        try (var in = file.getInputStream()) {
            stored = storage.store(in, file.getOriginalFilename(), contentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return repository.save(Broadcast.recordedFromFile(
                    title, stored.storedName(), stored.originalName(), stored.contentType(), stored.sizeBytes()));
        } catch (RuntimeException e) {
            storage.delete(stored.storedName()); // do not leave an orphan file if the DB write fails
            throw e;
        }
    }

    /** A freshly created live broadcast together with its one-time plain-text broadcaster key. */
    public record CreatedLive(Broadcast broadcast, String broadcasterKey) {
    }

    /** Creates a READY live broadcast; it becomes LIVE when the broadcaster connects to the signaling socket. */
    public CreatedLive createLive(String title) {
        String key = BroadcasterKeys.generate();
        Broadcast saved = repository.save(Broadcast.live(validateTitle(title), BroadcasterKeys.hash(key)));
        return new CreatedLive(saved, key);
    }

    /** Loads a live broadcast and checks the broadcaster key (403 on mismatch). */
    @Transactional(readOnly = true)
    public Broadcast authenticateBroadcaster(long id, String broadcasterKey) {
        Broadcast b = get(id);
        requireLive(b);
        if (!BroadcasterKeys.matches(broadcasterKey, b.getBroadcasterKeyHash())) {
            throw new ForbiddenException("방송자 키가 올바르지 않습니다.");
        }
        return b;
    }

    /** READY -> LIVE when the broadcaster phone connects. Idempotent; ignored for other states. */
    public Broadcast markLive(long id) {
        Broadcast b = get(id);
        if (b.getStatus() != BroadcastStatus.READY) {
            return b;
        }
        b.markLive();
        return repository.save(b);
    }

    /** Ends a live broadcast on the broadcaster's request (key required). Idempotent. */
    public Broadcast endLive(long id, String broadcasterKey) {
        authenticateBroadcaster(id, broadcasterKey);
        return endInternal(id);
    }

    /** Ends a live broadcast without a key: used by the server itself (grace period expired, restart cleanup). */
    public Broadcast endBySystem(long id) {
        return endInternal(id);
    }

    private Broadcast endInternal(long id) {
        Broadcast b = get(id);
        requireLive(b);
        if (b.getStatus() == BroadcastStatus.ENDED) {
            return b;
        }
        b.markEnded();
        Broadcast saved = repository.save(b);
        events.publishEvent(new BroadcastEndedEvent(id));
        return saved;
    }

    /** Live broadcasts left READY/LIVE by a previous server process can no longer have a connected broadcaster. */
    public int endOrphanedLive() {
        List<Broadcast> orphans = repository.findAllByTypeAndStatusIn(
                BroadcastType.LIVE, List.of(BroadcastStatus.READY, BroadcastStatus.LIVE));
        orphans.forEach(b -> endInternal(b.getId()));
        return orphans.size();
    }

    private static void requireLive(Broadcast b) {
        if (!b.isLiveType()) {
            throw new InvalidRequestException("라이브 방송이 아닙니다.");
        }
    }

    /**
     * Lists broadcasts, newest first, with LIVE ones moved to the top. Without a {@code status} filter,
     * READY broadcasts (created but not streaming yet) are hidden from the public list.
     */
    @Transactional(readOnly = true)
    public List<Broadcast> list(BroadcastType type, BroadcastStatus status) {
        Specification<Broadcast> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            } else {
                predicates.add(cb.notEqual(root.get("status"), BroadcastStatus.READY));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        List<Broadcast> found = new ArrayList<>(
                repository.findAll(spec, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        // Stable sort: LIVE first, otherwise keep the newest-first order from the database.
        found.sort(Comparator.comparing((Broadcast b) -> b.getStatus() == BroadcastStatus.LIVE ? 0 : 1));
        return found;
    }

    @Transactional(readOnly = true)
    public Broadcast get(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("방송을 찾을 수 없습니다: " + id));
    }

    /** Returns the stored video file of a broadcast; 404 for URL-based or live broadcasts, or missing files. */
    @Transactional(readOnly = true)
    public Resource loadVideo(Broadcast b) {
        if (!b.isStoredFile()) {
            throw new NotFoundException("이 방송은 업로드된 영상 파일이 없습니다(외부 URL 또는 라이브 방송).");
        }
        try {
            return storage.load(b.getStoredFileName());
        } catch (VideoNotFoundException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    static String validateTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new InvalidRequestException("제목은 필수입니다.");
        }
        String t = title.trim();
        if (t.length() > MAX_TITLE_LENGTH) {
            throw new InvalidRequestException("제목은 " + MAX_TITLE_LENGTH + "자 이하여야 합니다.");
        }
        return t;
    }

    private static String validateUrl(String url) {
        if (url.length() > MAX_URL_LENGTH) {
            throw new InvalidRequestException("영상 URL이 너무 깁니다.");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            boolean httpLike = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!httpLike || uri.getHost() == null) {
                throw new InvalidRequestException("영상 URL은 http 또는 https 주소여야 합니다.");
            }
        } catch (URISyntaxException e) {
            throw new InvalidRequestException("영상 URL 형식이 올바르지 않습니다.");
        }
        return url;
    }
}
