package com.chzzk.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalVideoStorageTest {

    @TempDir
    Path dir;

    @Test
    void storesUnderRandomNameAndLoadsBack() throws Exception {
        LocalVideoStorage storage = new LocalVideoStorage(dir.toString());
        StoredVideo stored = storage.store(new ByteArrayInputStream(new byte[] {1, 2, 3}), "../../evil.MP4", "video/mp4");

        assertThat(stored.storedName()).doesNotContain("evil").doesNotContain("/").endsWith(".mp4");
        assertThat(stored.sizeBytes()).isEqualTo(3);
        assertThat(storage.load(stored.storedName()).contentLength()).isEqualTo(3);

        storage.delete(stored.storedName());
        assertThatThrownBy(() -> storage.load(stored.storedName())).isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void rejectsPathTraversalNames() {
        LocalVideoStorage storage = new LocalVideoStorage(dir.toString());
        assertThatThrownBy(() -> storage.load("../secret.txt")).isInstanceOf(VideoNotFoundException.class);
        assertThatThrownBy(() -> storage.load("/etc/passwd")).isInstanceOf(VideoNotFoundException.class);
    }
}
