package com.chzzk.broadcast;

import com.chzzk.common.InvalidRequestException;
import com.chzzk.common.NotFoundException;
import com.chzzk.storage.StoredVideo;
import com.chzzk.storage.VideoNotFoundException;
import com.chzzk.storage.VideoStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class BroadcastService {

    static final int MAX_TITLE_LENGTH = 200;
    static final int MAX_URL_LENGTH = 2000;

    private final BroadcastRepository repository;
    private final VideoStorage storage;

    public BroadcastService(BroadcastRepository repository, VideoStorage storage) {
        this.repository = repository;
        this.storage = storage;
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

    @Transactional(readOnly = true)
    public List<Broadcast> list(BroadcastType type) {
        return type == null
                ? repository.findAllByOrderByCreatedAtDescIdDesc()
                : repository.findAllByTypeOrderByCreatedAtDescIdDesc(type);
    }

    @Transactional(readOnly = true)
    public Broadcast get(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("방송을 찾을 수 없습니다: " + id));
    }

    /** Returns the stored video file of a broadcast; 404 for URL-based broadcasts or missing files. */
    @Transactional(readOnly = true)
    public Resource loadVideo(Broadcast b) {
        if (!b.isStoredFile()) {
            throw new NotFoundException("이 방송은 업로드된 영상 파일이 없습니다(외부 URL 방송).");
        }
        try {
            return storage.load(b.getStoredFileName());
        } catch (VideoNotFoundException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    private static String validateTitle(String title) {
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
