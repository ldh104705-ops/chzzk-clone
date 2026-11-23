package com.chzzk.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chzzk.MinioTestContainer;
import com.chzzk.RedisTestContainer;
import com.chzzk.TestUsers;
import com.chzzk.TestcontainersConfiguration;
import com.chzzk.auth.TokenDenylist;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole app in cluster mode: shared state in Redis (logout denylist), videos in MinIO (S3 API), probes and the
 * demo instance header. A second Redis connection stands in for "another backend instance".
 */
@SpringBootTest(properties = {
        "app.cluster.mode=redis",
        "app.jwt.secret=0123456789abcdef0123456789abcdef-cluster-test",
        "app.storage.type=s3",
        "app.storage.s3.bucket=cluster-test",
        "app.storage.s3.create-bucket=true",
        "app.instance-header.enabled=true",
        "app.live.reaper-interval-seconds=3600"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ClusterModeApiTest {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", RedisTestContainer::host);
        registry.add("spring.data.redis.port", RedisTestContainer::port);
        registry.add("app.storage.s3.endpoint", MinioTestContainer::endpoint);
        registry.add("app.storage.s3.access-key", MinioTestContainer::accessKey);
        registry.add("app.storage.s3.secret-key", MinioTestContainer::secretKey);
    }

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestUsers users;

    @Autowired
    TokenDenylist denylist;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void logoutOnOneInstanceInvalidatesTheTokenOnEveryInstance() throws Exception {
        TestUsers.Account me = users.register("cluster");
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, me.bearer())).andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, me.bearer())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, me.bearer())).andExpect(status().isUnauthorized());

        // The entry is in Redis (not in this JVM) with a TTL no longer than the token life (12h default).
        String payload = new String(java.util.Base64.getUrlDecoder().decode(me.token().split("[.]")[1]));
        String jti = json.readTree(payload).get("jti").asString();
        Long ttl = redis.getExpire("auth:denylist:" + jti);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(12 * 3600L);

        // "Another instance": separate connection and denylist object, same Redis.
        var otherFactory = new LettuceConnectionFactory(RedisTestContainer.host(), RedisTestContainer.port());
        otherFactory.afterPropertiesSet();
        try {
            var otherRedis = new StringRedisTemplate(otherFactory);
            otherRedis.afterPropertiesSet();

            assertThat(new com.chzzk.auth.RedisTokenDenylist(otherRedis).isRevoked(jti)).isTrue();
            assertThat(new com.chzzk.auth.RedisTokenDenylist(otherRedis).isRevoked("some-other-jti")).isFalse();
        } finally {
            otherFactory.destroy();
        }
        assertThat(denylist).isInstanceOf(com.chzzk.auth.RedisTokenDenylist.class);
    }

    @Test
    void uploadedVideoLivesInTheBucketAndServesRangeRequests() throws Exception {
        TestUsers.Account me = users.register("uploader");
        byte[] bytes = "0123456789abcdefghij".getBytes();
        String body = mvc.perform(multipart("/api/broadcasts/recorded")
                        .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", bytes))
                        .param("title", "S3 영상")
                        .header(HttpHeaders.AUTHORIZATION, me.bearer()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(body).get("id").asLong();

        mvc.perform(get("/api/broadcasts/" + id + "/video"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", "20"))
                .andExpect(content().bytes(bytes));

        mvc.perform(get("/api/broadcasts/" + id + "/video").header(HttpHeaders.RANGE, "bytes=5-9"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 5-9/20"))
                .andExpect(header().string("Content-Length", "5"))
                .andExpect(content().bytes("56789".getBytes()));

        mvc.perform(get("/api/broadcasts/" + id + "/video").header(HttpHeaders.RANGE, "bytes=15-"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 15-19/20"))
                .andExpect(content().bytes("fghij".getBytes()));

        mvc.perform(delete("/api/broadcasts/" + id).header(HttpHeaders.AUTHORIZATION, me.bearer())).andExpect(status().isNoContent());
        mvc.perform(get("/api/broadcasts/" + id + "/video")).andExpect(status().isNotFound());
    }

    @Test
    void probesAndInstanceHeader() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/broadcasts")).andExpect(status().isOk()).andExpect(header().exists("X-Instance"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andExpect(header().exists("X-Instance"));
    }
}
