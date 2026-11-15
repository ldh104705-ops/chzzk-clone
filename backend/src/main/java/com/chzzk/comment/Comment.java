package com.chzzk.comment;

import com.chzzk.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** A comment on one broadcast, written by a logged-in user (the author). Deleted with its broadcast (DB cascade). */
@Entity
@Table(name = "comment")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "broadcast_id", nullable = false, updatable = false)
    private Long broadcastId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private User author;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Comment() {
    }

    public Comment(Long broadcastId, User author, String content) {
        this.broadcastId = broadcastId;
        this.author = author;
        this.content = content;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void edit(String newContent) {
        this.content = newContent;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getBroadcastId() { return broadcastId; }
    public User getAuthor() { return author; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
