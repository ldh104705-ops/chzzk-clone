package com.chzzk.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chzzk.MinioTestContainer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

/** S3VideoStorage against a real MinIO server (the same S3 API Cloudflare R2 speaks). */
class S3VideoStorageTest {

    private static S3VideoStorage storage;

    @BeforeAll
    static void start() {
        storage = new S3VideoStorage(MinioTestContainer.endpoint(), "us-east-1", "test-videos",
                MinioTestContainer.accessKey(), MinioTestContainer.secretKey(), true, true);
    }


    private static StoredVideo store(String content, String name) {
        return storage.store(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), name, "video/mp4");
    }

    @Test
    void storesUnderRandomNameAndLoadsBackWithLength() throws Exception {
        StoredVideo stored = store("0123456789", "../../evil.MP4");

        assertThat(stored.storedName()).doesNotContain("evil").doesNotContain("/").endsWith(".mp4");
        assertThat(stored.sizeBytes()).isEqualTo(10);
        Resource loaded = storage.load(stored.storedName());
        assertThat(loaded.contentLength()).isEqualTo(10);
        try (InputStream in = loaded.getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("0123456789");
        }
    }

    @Test
    void skipTurnsIntoARangedGetObject() throws Exception {
        StoredVideo stored = store("0123456789", "clip.mp4");
        try (InputStream in = storage.load(stored.storedName()).getInputStream()) {
            assertThat(in.skip(4)).isEqualTo(4); // lazy: no bytes transferred yet, becomes Range: bytes=4-
            assertThat(new String(in.readNBytes(3), StandardCharsets.UTF_8)).isEqualTo("456"); // like Spring copying bytes 4-6
        }
    }

    @Test
    void deleteRemovesTheObjectAndIsIdempotent() {
        StoredVideo stored = store("bye", "clip.mp4");
        storage.delete(stored.storedName());
        storage.delete(stored.storedName()); // missing objects are ignored
        assertThatThrownBy(() -> storage.load(stored.storedName())).isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void rejectsUnsafeNamesBeforeTouchingTheBucket() {
        assertThatThrownBy(() -> storage.load("../secret")).isInstanceOf(VideoNotFoundException.class);
        assertThatThrownBy(() -> storage.delete("a/b")).isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void refusesToStartWithoutCredentials() {
        assertThatThrownBy(() -> new S3VideoStorage("", "auto", "b", "", "", true, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_STORAGE_S3_ACCESS_KEY");
    }
}
