package com.chzzk.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chzzk.TestcontainersConfiguration;
import com.chzzk.auth.InMemoryTokenDenylist;
import com.chzzk.auth.TokenDenylist;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The default (local) mode must not need Redis: in-memory denylist, and a health endpoint that is not red because of it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LocalModeTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TokenDenylist denylist;

    @Test
    void localModeUsesInMemoryStateAndHealthIsUpWithoutRedis() throws Exception {
        assertThat(denylist).isInstanceOf(InMemoryTokenDenylist.class);
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/api/broadcasts")).andExpect(status().isOk()).andExpect(header().doesNotExist("X-Instance"));
    }
}
