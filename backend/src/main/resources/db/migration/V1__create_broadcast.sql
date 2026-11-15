CREATE TABLE broadcast (
    id                 BIGSERIAL PRIMARY KEY,
    title              VARCHAR(200)  NOT NULL,
    type               VARCHAR(20)   NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    stored_file_name   VARCHAR(255),
    original_file_name VARCHAR(255),
    content_type       VARCHAR(255),
    size_bytes         BIGINT,
    video_url          VARCHAR(2000),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_broadcast_type   CHECK (type IN ('RECORDED')),
    CONSTRAINT chk_broadcast_status CHECK (status IN ('ENDED')),
    -- A recorded broadcast has exactly one video source: stored file OR external URL.
    CONSTRAINT chk_broadcast_recorded_source CHECK (
        type <> 'RECORDED' OR ((stored_file_name IS NULL) <> (video_url IS NULL))
    )
);

CREATE INDEX idx_broadcast_created_at ON broadcast (created_at DESC, id DESC);
