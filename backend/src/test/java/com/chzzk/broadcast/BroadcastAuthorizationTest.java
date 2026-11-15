package com.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chzzk.TestUsers;
import com.chzzk.TestUsers.Account;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 3 authorization for broadcasts, as three actors: anonymous (401), the owner (allowed), another logged-in
 * user (403). Every denied call must leave the data untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BroadcastAuthorizationTest {

    private static final Path STORAGE_DIR = createTempDir();
    private final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.dir", STORAGE_DIR::toString);
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("chzzk-test-auth-uploads");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    BroadcastRepository repository;

    @Autowired
    TestUsers users;

    Account owner;
    Account other;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        owner = users.register("owner");
        other = users.register("other");
    }

    private long createUrlBroadcast(Account by, String title) throws Exception {
        String location = mvc.perform(multipart("/api/broadcasts/recorded").header("Authorization", by.bearer())
                        .param("title", title).param("videoUrl", "https://example.com/a.mp4"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private long createLive(Account by, String title) throws Exception {
        String body = mvc.perform(post("/api/broadcasts/live").header("Authorization", by.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private ResultActions rename(Account by, long id, String title) throws Exception {
        var request = patch("/api/broadcasts/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\"}");
        return mvc.perform(by == null ? request : request.header("Authorization", by.bearer()));
    }

    @Test
    void anonymousCannotCreateAnything() throws Exception {
        mvc.perform(multipart("/api/broadcasts/recorded").param("title", "t").param("videoUrl", "https://example.com/a.mp4"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(post("/api/broadcasts/live").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"t\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(repository.count()).isZero();
    }

    @Test
    void anonymousCanListAndWatch() throws Exception {
        long id = createUrlBroadcast(owner, "공개");
        mvc.perform(get("/api/broadcasts")).andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("공개"));
        mvc.perform(get("/api/broadcasts/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerNickname").value("owner"));
    }

    @Test
    void anonymousCannotEditDeleteOrEnd() throws Exception {
        long recorded = createUrlBroadcast(owner, "원본");
        long live = createLive(owner, "라이브");

        rename(null, recorded, "해킹").andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/broadcasts/{id}", recorded)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/broadcasts/{id}/end", live)).andExpect(status().isUnauthorized());

        assertThat(repository.findById(recorded).orElseThrow().getTitle()).isEqualTo("원본");
        assertThat(repository.findById(live).orElseThrow().getStatus()).isEqualTo(BroadcastStatus.READY);
    }

    @Test
    void anotherUserGets403ForEditDeleteAndEnd() throws Exception {
        long recorded = createUrlBroadcast(owner, "원본");
        long live = createLive(owner, "라이브");

        rename(other, recorded, "해킹").andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(delete("/api/broadcasts/{id}", recorded).header("Authorization", other.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/broadcasts/{id}/end", live).header("Authorization", other.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/broadcasts/{id}", live).header("Authorization", other.bearer()))
                .andExpect(status().isForbidden());

        assertThat(repository.findById(recorded).orElseThrow().getTitle()).isEqualTo("원본");
        assertThat(repository.findById(live).orElseThrow().getStatus()).isEqualTo(BroadcastStatus.READY);
    }

    @Test
    void ownerCanRenameAndTitleIsValidated() throws Exception {
        long id = createUrlBroadcast(owner, "이전 제목");
        rename(owner, id, "  새 제목 ").andExpect(status().isOk()).andExpect(jsonPath("$.title").value("새 제목"));
        rename(owner, id, "   ").andExpect(status().isBadRequest());
        rename(owner, id, "x".repeat(201)).andExpect(status().isBadRequest());
        rename(owner, id + 1000, "없는 방송").andExpect(status().isNotFound());
        assertThat(repository.findById(id).orElseThrow().getTitle()).isEqualTo("새 제목");
    }

    @Test
    void ownerEndsALiveBroadcast() throws Exception {
        long live = createLive(owner, "종료");
        mvc.perform(post("/api/broadcasts/{id}/end", live).header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"));
    }

    @Test
    void ownerDeletesABroadcastTogetherWithItsVideoFile() throws Exception {
        MockMultipartFile video = new MockMultipartFile("file", "clip.mp4", "video/mp4", "0123456789".getBytes());
        String location = mvc.perform(multipart("/api/broadcasts/recorded").file(video)
                        .header("Authorization", owner.bearer()).param("title", "삭제"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        Path stored = STORAGE_DIR.resolve(repository.findById(id).orElseThrow().getStoredFileName());
        assertThat(stored).exists();

        mvc.perform(delete("/api/broadcasts/{id}", id).header("Authorization", owner.bearer()))
                .andExpect(status().isNoContent());

        assertThat(repository.findById(id)).isEmpty();
        assertThat(stored).doesNotExist();
        mvc.perform(get("/api/broadcasts/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/broadcasts/{id}", id).header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanDeleteALiveBroadcastWhichEndsFirst() throws Exception {
        long live = createLive(owner, "삭제될 라이브");
        mvc.perform(delete("/api/broadcasts/{id}", live).header("Authorization", owner.bearer()))
                .andExpect(status().isNoContent());
        assertThat(repository.findById(live)).isEmpty();
    }

    @Test
    void broadcastsFromBeforeStep3HaveNoOwnerAndCannotBeManaged() throws Exception {
        long legacy = repository.save(Broadcast.recordedFromUrl(null, "옛 방송", "https://example.com/old.mp4")).getId();

        rename(owner, legacy, "내 것").andExpect(status().isForbidden());
        mvc.perform(delete("/api/broadcasts/{id}", legacy).header("Authorization", owner.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/broadcasts/{id}", legacy)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(org.hamcrest.Matchers.nullValue()));
    }
}
