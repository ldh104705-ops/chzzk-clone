package com.chzzk.broadcast;

import com.chzzk.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** Broadcast metadata plus the location of its video (stored file or external URL). */
@Entity
@Table(name = "broadcast")
public class Broadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BroadcastType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BroadcastStatus status;

    @Column(name = "stored_file_name")
    private String storedFileName;

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "video_url", length = 2000)
    private String videoUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** Who created (and may manage) this broadcast. Null only for rows created before step 3. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "owner_id")
    private User owner;

    protected Broadcast() {
    }

    private Broadcast(User owner, String title) {
        this.owner = owner;
        this.title = title;
        this.type = BroadcastType.RECORDED;
        this.status = BroadcastStatus.ENDED;
        this.createdAt = Instant.now();
    }

    public static Broadcast recordedFromFile(User owner, String title, String storedFileName, String originalFileName,
                                             String contentType, long sizeBytes) {
        Broadcast b = new Broadcast(owner, title);
        b.storedFileName = storedFileName;
        b.originalFileName = originalFileName;
        b.contentType = contentType;
        b.sizeBytes = sizeBytes;
        return b;
    }

    public static Broadcast recordedFromUrl(User owner, String title, String videoUrl) {
        Broadcast b = new Broadcast(owner, title);
        b.videoUrl = videoUrl;
        return b;
    }

    /** A live broadcast starts READY: created, but the phone has not begun streaming yet. */
    public static Broadcast live(User owner, String title) {
        Broadcast b = new Broadcast(owner, title);
        b.type = BroadcastType.LIVE;
        b.status = BroadcastStatus.READY;
        return b;
    }

    public void rename(String newTitle) {
        this.title = newTitle;
    }

    /** READY -> LIVE (no-op in any other state). */
    public void markLive() {
        if (status == BroadcastStatus.READY) {
            status = BroadcastStatus.LIVE;
            startedAt = Instant.now();
        }
    }

    /** Any state -> ENDED (no-op when already ENDED). */
    public void markEnded() {
        if (status != BroadcastStatus.ENDED) {
            status = BroadcastStatus.ENDED;
            endedAt = Instant.now();
        }
    }

    public boolean isLiveType() {
        return type == BroadcastType.LIVE;
    }

    public boolean isStoredFile() {
        return storedFileName != null;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public BroadcastType getType() { return type; }
    public BroadcastStatus getStatus() { return status; }
    public String getStoredFileName() { return storedFileName; }
    public String getOriginalFileName() { return originalFileName; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getVideoUrl() { return videoUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public User getOwner() { return owner; }
    public Long getOwnerId() { return owner == null ? null : owner.getId(); }
}
