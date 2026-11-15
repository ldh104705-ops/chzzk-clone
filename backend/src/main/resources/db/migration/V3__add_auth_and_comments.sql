-- Step 3: accounts, broadcast ownership and comments.

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    -- Login id, always lower-case (the application normalises it; the CHECK enforces it).
    username      VARCHAR(30)  NOT NULL,
    -- BCrypt hash ("$2a$10$..." is 60 characters); the password itself is never stored.
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT chk_users_username CHECK (username ~ '^[a-z0-9_]{3,30}$'),
    CONSTRAINT chk_users_nickname CHECK (char_length(btrim(nickname)) BETWEEN 1 AND 20)
);

-- Owner of a broadcast. Nullable ONLY because rows created in steps 1-2 had no owner; the application always
-- sets it, and an ownerless broadcast cannot be edited, ended or deleted through the API.
ALTER TABLE broadcast ADD COLUMN owner_id BIGINT REFERENCES users (id);
CREATE INDEX idx_broadcast_owner ON broadcast (owner_id);

-- The step-2 broadcaster key is replaced by owner checks on the logged-in user, so remove it cleanly.
-- Dropping the column would drop chk_broadcast_live_shape too; re-create it without the key.
ALTER TABLE broadcast DROP CONSTRAINT chk_broadcast_live_shape;
ALTER TABLE broadcast DROP COLUMN broadcaster_key_hash;
ALTER TABLE broadcast ADD CONSTRAINT chk_broadcast_live_shape CHECK (
    type <> 'LIVE' OR (stored_file_name IS NULL AND video_url IS NULL)
);

CREATE TABLE comment (
    id           BIGSERIAL PRIMARY KEY,
    broadcast_id BIGINT NOT NULL REFERENCES broadcast (id) ON DELETE CASCADE,
    author_id    BIGINT NOT NULL REFERENCES users (id),
    content      VARCHAR(500) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_comment_content CHECK (char_length(btrim(content)) BETWEEN 1 AND 500)
);

-- Newest-first listing of one broadcast's comments.
CREATE INDEX idx_comment_broadcast_created ON comment (broadcast_id, created_at DESC, id DESC);
CREATE INDEX idx_comment_author ON comment (author_id);
