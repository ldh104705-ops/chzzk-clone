package com.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chzzk.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Integration tests against a real PostgreSQL (Testcontainers). No authentication is involved in step 1. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BroadcastApiTest {

    private static final Path STORAGE_DIR = createTempDir();
    private static final byte[] VIDEO_BYTES = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes();

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.dir", STORAGE_DIR::toString);
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("chzzk-test-uploads");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    BroadcastRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private static MockMultipartFile video() {
        return new MockMultipartFile("file", "clip.mp4", "video/mp4", VIDEO_BYTES);
    }

    private long registerFileBroadcast(String title) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/broadcasts/recorded").file(video()).param("title", title))
                .andExpect(status().isCreated())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    void registersUploadedFile() throws Exception {
        mvc.perform(multipart("/api/broadcasts/recorded").file(video()).param("title", "  업로드 방송  "))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.title").value("업로드 방송"))
                .andExpect(jsonPath("$.type").value("RECORDED"))
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.sourceType").value("FILE"))
                .andExpect(jsonPath("$.originalFileName").value("clip.mp4"))
                .andExpect(jsonPath("$.contentType").value("video/mp4"))
                .andExpect(jsonPath("$.sizeBytes").value(VIDEO_BYTES.length))
                .andExpect(jsonPath("$.playbackUrl").value(org.hamcrest.Matchers.matchesPattern("/api/broadcasts/\\d+/video")));

        Broadcast saved = repository.findAll().getFirst();
        assertThat(saved.getStoredFileName()).endsWith(".mp4").isNotEqualTo("clip.mp4");
        assertThat(STORAGE_DIR.resolve(saved.getStoredFileName())).exists();
    }

    @Test
    void registersExternalUrl() throws Exception {
        mvc.perform(multipart("/api/broadcasts/recorded")
                        .param("title", "URL 방송")
                        .param("videoUrl", "https://example.com/videos/a.mp4"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("URL"))
                .andExpect(jsonPath("$.playbackUrl").value("https://example.com/videos/a.mp4"));

        assertThat(repository.findAll()).singleElement()
                .satisfies(b -> assertThat(b.getVideoUrl()).isEqualTo("https://example.com/videos/a.mp4"));
    }

    @Test
    void rejectsMissingTitle() throws Exception {
        mvc.perform(multipart("/api/broadcasts/recorded").param("videoUrl", "https://example.com/a.mp4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(multipart("/api/broadcasts/recorded").param("title", "   ").file(video()))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsBothOrNeitherSource() throws Exception {
        mvc.perform(multipart("/api/broadcasts/recorded").file(video()).param("title", "t")
                        .param("videoUrl", "https://example.com/a.mp4"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/broadcasts/recorded").param("title", "t"))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsBadUrl() throws Exception {
        for (String bad : new String[] {"not a url", "ftp://example.com/a.mp4", "javascript:alert(1)", "http://"}) {
            mvc.perform(multipart("/api/broadcasts/recorded").param("title", "t").param("videoUrl", bad))
                    .andExpect(status().isBadRequest());
        }
        assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsNonVideoFile() throws Exception {
        MockMultipartFile text = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
        mvc.perform(multipart("/api/broadcasts/recorded").file(text).param("title", "t"))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void listsNewestFirstAndFiltersByType() throws Exception {
        mvc.perform(multipart("/api/broadcasts/recorded").param("title", "첫번째").param("videoUrl", "https://example.com/1.mp4"))
                .andExpect(status().isCreated());
        mvc.perform(multipart("/api/broadcasts/recorded").param("title", "두번째").param("videoUrl", "https://example.com/2.mp4"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/broadcasts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].title").value("두번째"))
                .andExpect(jsonPath("$[1].title").value("첫번째"));
        mvc.perform(get("/api/broadcasts").param("type", "RECORDED"))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/broadcasts").param("type", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsDetailAndNotFound() throws Exception {
        long id = registerFileBroadcast("상세");
        mvc.perform(get("/api/broadcasts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("상세"));
        mvc.perform(get("/api/broadcasts/{id}", id + 1000))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/api/broadcasts/{id}/video", id + 1000))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamsVideoContent() throws Exception {
        long id = registerFileBroadcast("재생");
        mvc.perform(get("/api/broadcasts/{id}/video", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(content().bytes(VIDEO_BYTES));
    }

    @Test
    void honorsRangeHeader() throws Exception {
        long id = registerFileBroadcast("범위");
        mvc.perform(get("/api/broadcasts/{id}/video", id).header("Range", "bytes=5-14"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 5-14/" + VIDEO_BYTES.length))
                .andExpect(header().string("Content-Length", "10"))
                .andExpect(content().bytes(java.util.Arrays.copyOfRange(VIDEO_BYTES, 5, 15)));
    }

    @Test
    void videoEndpointIs404ForUrlBroadcast() throws Exception {
        MvcResult result = mvc.perform(multipart("/api/broadcasts/recorded").param("title", "t")
                        .param("videoUrl", "https://example.com/a.mp4")).andReturn();
        String location = result.getResponse().getHeader("Location");
        mvc.perform(get(location + "/video")).andExpect(status().isNotFound());
    }

    @Test
    void metadataAndFileArePersisted() throws Exception {
        long id = registerFileBroadcast("영속성");

        // Read back through the repository (new DB read) and through a fresh request.
        Broadcast fromDb = repository.findById(id).orElseThrow();
        assertThat(fromDb.getTitle()).isEqualTo("영속성");
        assertThat(fromDb.getType()).isEqualTo(BroadcastType.RECORDED);
        assertThat(fromDb.getCreatedAt()).isNotNull();
        assertThat(Files.readAllBytes(STORAGE_DIR.resolve(fromDb.getStoredFileName()))).isEqualTo(VIDEO_BYTES);

        mvc.perform(get("/api/broadcasts/{id}", id)).andExpect(jsonPath("$.title").value("영속성"));
    }
}
