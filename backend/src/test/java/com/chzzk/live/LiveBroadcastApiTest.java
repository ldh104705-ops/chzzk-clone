package com.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chzzk.TestcontainersConfiguration;
import com.chzzk.broadcast.Broadcast;
import com.chzzk.broadcast.BroadcastRepository;
import com.chzzk.broadcast.BroadcastService;
import com.chzzk.broadcast.BroadcastStatus;
import com.chzzk.broadcast.BroadcasterKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** REST side of step 2: create/end live broadcasts, broadcaster key checks, status filtering, ICE servers. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LiveBroadcastApiTest {

    private static final String KEY_HEADER = "X-Broadcaster-Key";
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    BroadcastRepository repository;

    @Autowired
    BroadcastService service;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private JsonNode createLive(String title) throws Exception {
        MvcResult result = mvc.perform(post("/api/broadcasts/live")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createsReadyLiveBroadcastAndReturnsKeyOnce() throws Exception {
        mvc.perform(post("/api/broadcasts/live").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  내 라이브  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.broadcast.title").value("내 라이브"))
                .andExpect(jsonPath("$.broadcast.type").value("LIVE"))
                .andExpect(jsonPath("$.broadcast.status").value("READY"))
                .andExpect(jsonPath("$.broadcast.sourceType").value("LIVE"))
                .andExpect(jsonPath("$.broadcast.playbackUrl").value(nullValue()))
                .andExpect(jsonPath("$.broadcasterKey").isNotEmpty());
    }

    @Test
    void storesOnlyAHashOfTheKey() throws Exception {
        JsonNode created = createLive("해시");
        String key = created.get("broadcasterKey").asString();
        Broadcast saved = repository.findAll().getFirst();

        assertThat(saved.getBroadcasterKeyHash()).isNotEqualTo(key).isEqualTo(BroadcasterKeys.hash(key)).hasSize(64);
        // The key must not leak through the normal read APIs.
        mvc.perform(get("/api/broadcasts/{id}", saved.getId()))
                .andExpect(jsonPath("$.broadcasterKey").doesNotExist())
                .andExpect(jsonPath("$.broadcasterKeyHash").doesNotExist());
    }

    @Test
    void rejectsMissingTitleAndBody() throws Exception {
        mvc.perform(post("/api/broadcasts/live").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/broadcasts/live").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/broadcasts/live").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
    }

    @Test
    void endsWithCorrectKeyAndIsIdempotent() throws Exception {
        JsonNode created = createLive("종료 테스트");
        long id = created.get("broadcast").get("id").asLong();
        String key = created.get("broadcasterKey").asString();
        service.markLive(id);

        mvc.perform(post("/api/broadcasts/{id}/end", id).header(KEY_HEADER, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty());
        mvc.perform(post("/api/broadcasts/{id}/end", id).header(KEY_HEADER, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(BroadcastStatus.ENDED);
    }

    @Test
    void endWithWrongOrMissingKeyIsForbiddenAndKeepsBroadcastLive() throws Exception {
        JsonNode created = createLive("보호");
        long id = created.get("broadcast").get("id").asLong();
        service.markLive(id);

        mvc.perform(post("/api/broadcasts/{id}/end", id).header(KEY_HEADER, "wrong-key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(post("/api/broadcasts/{id}/end", id))
                .andExpect(status().isForbidden());
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(BroadcastStatus.LIVE);
    }

    @Test
    void endOfUnknownOrRecordedBroadcastFails() throws Exception {
        mvc.perform(post("/api/broadcasts/{id}/end", 987654).header(KEY_HEADER, "k"))
                .andExpect(status().isNotFound());

        mvc.perform(multipart("/api/broadcasts/recorded").param("title", "녹화").param("videoUrl", "https://example.com/a.mp4"))
                .andExpect(status().isCreated());
        long recordedId = repository.findAll().getFirst().getId();
        mvc.perform(post("/api/broadcasts/{id}/end", recordedId).header(KEY_HEADER, "k"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/broadcasts/{id}/video", createLive("라이브").get("broadcast").get("id").asLong()))
                .andExpect(status().isNotFound());
    }

    @Test
    void filtersByTypeAndStatusAndShowsLiveFirst() throws Exception {
        mvc.perform(multipart("/api/broadcasts/recorded").param("title", "녹화A").param("videoUrl", "https://example.com/a.mp4"))
                .andExpect(status().isCreated());
        JsonNode live = createLive("라이브B");
        long liveId = live.get("broadcast").get("id").asLong();
        service.markLive(liveId);
        JsonNode ended = createLive("끝난라이브C");
        long endedId = ended.get("broadcast").get("id").asLong();
        service.markLive(endedId);
        service.endLive(endedId, ended.get("broadcasterKey").asString());
        createLive("준비중D"); // READY: created but never started

        // Default list: READY hidden; the LIVE one comes first even though a newer ENDED one exists.
        mvc.perform(get("/api/broadcasts"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].title").value("라이브B"))
                .andExpect(jsonPath("$[0].status").value("LIVE"));

        mvc.perform(get("/api/broadcasts").param("type", "LIVE").param("status", "LIVE"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(liveId));
        mvc.perform(get("/api/broadcasts").param("status", "ENDED"))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/broadcasts").param("type", "LIVE").param("status", "ENDED"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("끝난라이브C"));
        mvc.perform(get("/api/broadcasts").param("type", "RECORDED"))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/broadcasts").param("status", "READY"))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/broadcasts").param("status", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endsOrphanedLiveBroadcastsOnStartupCleanup() throws Exception {
        long liveId = createLive("고아").get("broadcast").get("id").asLong();
        service.markLive(liveId);

        assertThat(service.endOrphanedLive()).isEqualTo(1);
        assertThat(repository.findById(liveId).orElseThrow().getStatus()).isEqualTo(BroadcastStatus.ENDED);
        assertThat(service.endOrphanedLive()).isZero();
    }

    @Test
    void exposesIceServersFromConfiguration() throws Exception {
        mvc.perform(get("/api/live/ice-servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iceServers", hasSize(1))) // default: STUN only, no TURN configured
                .andExpect(jsonPath("$.iceServers[0].urls[0]").value("stun:stun.l.google.com:19302"))
                .andExpect(jsonPath("$.iceServers[0].username").doesNotExist());
    }
}
