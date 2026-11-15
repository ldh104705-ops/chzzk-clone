-- Step 2: live broadcasts. A LIVE-type row goes READY (created, stream not started) -> LIVE -> ENDED.
ALTER TABLE broadcast
    ADD COLUMN started_at           TIMESTAMP WITH TIME ZONE,
    ADD COLUMN ended_at             TIMESTAMP WITH TIME ZONE,
    -- SHA-256 hex of the secret broadcaster key; the key itself is never stored.
    ADD COLUMN broadcaster_key_hash VARCHAR(64);

-- Widen the allowed values (PostgreSQL cannot alter a CHECK in place, so drop + re-add the same names).
ALTER TABLE broadcast
    DROP CONSTRAINT chk_broadcast_type,
    DROP CONSTRAINT chk_broadcast_status;
ALTER TABLE broadcast
    ADD CONSTRAINT chk_broadcast_type   CHECK (type IN ('RECORDED', 'LIVE')),
    ADD CONSTRAINT chk_broadcast_status CHECK (status IN ('READY', 'LIVE', 'ENDED')),
    -- A recorded broadcast is always already ENDED.
    ADD CONSTRAINT chk_broadcast_recorded_status CHECK (type <> 'RECORDED' OR status = 'ENDED'),
    -- A live broadcast has no video file/URL and always has a broadcaster key hash.
    ADD CONSTRAINT chk_broadcast_live_shape CHECK (
        type <> 'LIVE'
        OR (stored_file_name IS NULL AND video_url IS NULL AND broadcaster_key_hash IS NOT NULL)
    );

CREATE INDEX idx_broadcast_status ON broadcast (status);
