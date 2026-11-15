package com.chzzk.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A registered account. Only the BCrypt hash of the password is stored. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Login id, stored lower-case so that "Alice" and "alice" are the same account. */
    @Column(nullable = false, unique = true, length = 30)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String nickname;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(String username, String passwordHash, String nickname) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getNickname() { return nickname; }
    public Instant getCreatedAt() { return createdAt; }
}
