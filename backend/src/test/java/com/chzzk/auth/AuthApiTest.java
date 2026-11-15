package com.chzzk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chzzk.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** signup / login / me / logout through the real security filter chain and PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiTest {

    private static final String PASSWORD = "correct-horse-1";
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    private static String unique() {
        return "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private ResultActions signup(String username, String password, String nickname) throws Exception {
        return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(java.util.Map.of("username", username, "password", password, "nickname", nickname))));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(
                json.writeValueAsString(java.util.Map.of("username", username, "password", password))));
    }

    private String loginToken(String username) throws Exception {
        String body = login(username, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asString();
    }

    @Test
    void signupCreatesAnAccountAndStoresOnlyABcryptHash() throws Exception {
        String name = unique();
        signup(name.toUpperCase(), PASSWORD, "  닉네임  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(name)) // normalised to lower-case
                .andExpect(jsonPath("$.nickname").value("닉네임"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User saved = users.findByUsername(name).orElseThrow();
        assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
    }

    @Test
    void duplicateUsernameIsRejectedWith409() throws Exception {
        String name = unique();
        signup(name, PASSWORD, "첫번째").andExpect(status().isCreated());
        signup(name, "another-password", "두번째")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        signup(name.toUpperCase(), PASSWORD, "대문자").andExpect(status().isConflict()); // same account, other case
    }

    @Test
    void signupValidatesInput() throws Exception {
        signup("ab", PASSWORD, "닉").andExpect(status().isBadRequest());            // username too short
        signup("bad name!", PASSWORD, "닉").andExpect(status().isBadRequest());     // illegal characters
        signup(unique(), "short", "닉").andExpect(status().isBadRequest());         // password too short
        signup(unique(), "x".repeat(73), "닉").andExpect(status().isBadRequest());  // beyond BCrypt's 72 bytes
        signup(unique(), PASSWORD, "   ").andExpect(status().isBadRequest());       // blank nickname
        signup(unique(), PASSWORD, "n".repeat(21)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginReturnsATokenThatOpensMe() throws Exception {
        String name = unique();
        signup(name, PASSWORD, "로그인").andExpect(status().isCreated());

        String body = login(name, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(name))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).get("token").asString();

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(name))
                .andExpect(jsonPath("$.nickname").value("로그인"));
    }

    @Test
    void wrongPasswordAndUnknownUserAreBothRejectedWith401() throws Exception {
        String name = unique();
        signup(name, PASSWORD, "비번").andExpect(status().isCreated());

        login(name, "wrong-password-1").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        login(unique(), PASSWORD).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingOrInvalidTokenGets401InTheCommonJsonShape() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());

        String name = unique();
        signup(name, PASSWORD, "변조").andExpect(status().isCreated());
        String tampered = loginToken(name) + "x"; // signature no longer matches
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicReadsIgnoreAStaleTokenInsteadOfFailing() throws Exception {
        mvc.perform(get("/api/broadcasts").header("Authorization", "Bearer stale.or.garbage"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutReturns204AndTheTokenStopsWorking() throws Exception {
        String name = unique();
        signup(name, PASSWORD, "로그아웃").andExpect(status().isCreated());
        String token = loginToken(name);
        String other = loginToken(name); // a second login (another device) has its own token id

        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized()); // needs a token to log out
    }
}
