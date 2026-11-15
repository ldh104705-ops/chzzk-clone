package com.chzzk.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import com.chzzk.broadcast.BroadcastRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** Comments: public reading, login to write, author-only edit/delete, validation. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CommentApiTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    BroadcastRepository broadcasts;

    @Autowired
    CommentRepository comments;

    @Autowired
    TestUsers users;

    Account owner;
    Account author;
    Account other;
    long broadcastId;

    @BeforeEach
    void setUp() throws Exception {
        broadcasts.deleteAll(); // comments go with their broadcast (ON DELETE CASCADE)
        owner = users.register("owner");
        author = users.register("author");
        other = users.register("other");
        String location = mvc.perform(multipart("/api/broadcasts/recorded").header("Authorization", owner.bearer())
                        .param("title", "댓글 방송").param("videoUrl", "https://example.com/a.mp4"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        broadcastId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private ResultActions postComment(Account by, long onBroadcast, String content) throws Exception {
        var request = post("/api/broadcasts/{id}/comments", onBroadcast).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("content", content)));
        return mvc.perform(by == null ? request : request.header("Authorization", by.bearer()));
    }

    private long postOk(Account by, String content) throws Exception {
        String body = postComment(by, broadcastId, content).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private ResultActions edit(Account by, long id, String content) throws Exception {
        var request = patch("/api/comments/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("content", content)));
        return mvc.perform(by == null ? request : request.header("Authorization", by.bearer()));
    }

    @Test
    void anyoneCanReadCommentsWithAuthorNicknameAndId() throws Exception {
        postOk(author, "첫 댓글");
        postOk(other, "둘째 댓글");

        mvc.perform(get("/api/broadcasts/{id}/comments", broadcastId)) // no Authorization header
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].content").value("둘째 댓글")) // newest first
                .andExpect(jsonPath("$.items[0].authorNickname").value("other"))
                .andExpect(jsonPath("$.items[0].authorId").value(other.id()))
                .andExpect(jsonPath("$.items[1].authorId").value(author.id()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/broadcasts/{id}/comments", broadcastId + 1000)).andExpect(status().isNotFound());
    }

    @Test
    void commentListIsPaginated() throws Exception {
        for (int i = 1; i <= 3; i++) {
            postOk(author, "댓글 " + i);
        }
        mvc.perform(get("/api/broadcasts/{id}/comments", broadcastId).param("size", "2"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].content").value("댓글 3"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/broadcasts/{id}/comments", broadcastId).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].content").value("댓글 1"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void anonymousCannotPostAndNothingIsSaved() throws Exception {
        postComment(null, broadcastId, "익명").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        assertThat(comments.count()).isZero();
    }

    @Test
    void loggedInUserPostsAsThemselvesAndTheBodyCannotNameAnotherAuthor() throws Exception {
        mvc.perform(post("/api/broadcasts/{id}/comments", broadcastId)
                        .header("Authorization", author.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  안녕하세요  \",\"authorId\":" + other.id() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("안녕하세요"))
                .andExpect(jsonPath("$.authorId").value(author.id()))
                .andExpect(jsonPath("$.authorNickname").value("author"));
        assertThat(comments.findAll()).singleElement()
                .satisfies(c -> assertThat(c.getAuthor().getId()).isEqualTo(author.id()));
        postComment(author, broadcastId + 1000, "없는 방송").andExpect(status().isNotFound());
    }

    @Test
    void contentIsValidated() throws Exception {
        postComment(author, broadcastId, "   ").andExpect(status().isBadRequest());
        postComment(author, broadcastId, "x".repeat(501)).andExpect(status().isBadRequest());
        postComment(author, broadcastId, "x".repeat(500)).andExpect(status().isCreated()); // the limit itself is fine
        mvc.perform(post("/api/broadcasts/{id}/comments", broadcastId).header("Authorization", author.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(comments.count()).isEqualTo(1);

        long id = postOk(author, "수정 대상");
        edit(author, id, "").andExpect(status().isBadRequest());
        edit(author, id, "y".repeat(501)).andExpect(status().isBadRequest());
    }

    @Test
    void authorCanEditAndDelete() throws Exception {
        long id = postOk(author, "원문");

        edit(author, id, "수정본").andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("수정본"))
                .andExpect(jsonPath("$.authorId").value(author.id()));
        assertThat(comments.findById(id).orElseThrow().getContent()).isEqualTo("수정본");

        mvc.perform(delete("/api/comments/{id}", id).header("Authorization", author.bearer()))
                .andExpect(status().isNoContent());
        assertThat(comments.findById(id)).isEmpty();
        edit(author, id, "다시").andExpect(status().isNotFound());
        mvc.perform(delete("/api/comments/{id}", id).header("Authorization", author.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUsersGet403AndAnonymousGets401ForEditAndDelete() throws Exception {
        long id = postOk(author, "내 댓글");

        edit(other, id, "해킹").andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(delete("/api/comments/{id}", id).header("Authorization", other.bearer()))
                .andExpect(status().isForbidden());
        // Not even the broadcast owner may touch someone else's comment.
        edit(owner, id, "해킹").andExpect(status().isForbidden());
        mvc.perform(delete("/api/comments/{id}", id).header("Authorization", owner.bearer()))
                .andExpect(status().isForbidden());

        edit(null, id, "익명").andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/comments/{id}", id)).andExpect(status().isUnauthorized());

        assertThat(comments.findById(id).orElseThrow().getContent()).isEqualTo("내 댓글");
    }

    @Test
    void deletingABroadcastDeletesItsComments() throws Exception {
        postOk(author, "사라질 댓글");
        mvc.perform(delete("/api/broadcasts/{id}", broadcastId).header("Authorization", owner.bearer()))
                .andExpect(status().isNoContent());
        assertThat(comments.count()).isZero();
    }
}
