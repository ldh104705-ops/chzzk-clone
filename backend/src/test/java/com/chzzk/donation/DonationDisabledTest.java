package com.chzzk.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chzzk.TestUsers;
import com.chzzk.TestUsers.Account;
import com.chzzk.TestcontainersConfiguration;
import com.chzzk.broadcast.BroadcastRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** No TOSS_* keys (the default test configuration): the server runs and says clearly that donations are off. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeTossConfig.class})
class DonationDisabledTest {

    @Autowired MockMvc mvc;
    @Autowired TestUsers users;
    @Autowired DonationRepository donations;
    @Autowired BroadcastRepository broadcasts;
    @Autowired FakeTossPaymentsClient fake;

    @Test
    void configSaysDisabledAndLeaksNothing() throws Exception {
        mvc.perform(get("/api/payments/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.clientKey").doesNotExist());
    }

    @Test
    void createAndConfirmAnswer503WithAKoreanMessageAndStoreNothing() throws Exception {
        broadcasts.deleteAll();
        donations.deleteAll();
        fake.reset();
        Account owner = users.register("owner");
        Account donor = users.register("donor");
        String location = mvc.perform(multipart("/api/broadcasts/recorded").header("Authorization", owner.bearer())
                        .param("title", "방송").param("videoUrl", "https://example.com/a.mp4"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        long broadcastId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        mvc.perform(post("/api/donations").header("Authorization", donor.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"broadcastId\":" + broadcastId + ",\"amount\":5000}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("결제 설정이 없어 후원을 사용할 수 없습니다."));
        mvc.perform(post("/api/donations/confirm").header("Authorization", donor.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentKey\":\"k\",\"orderId\":\"donation_abc123\",\"amount\":5000}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(donations.count()).isZero();
        assertThat(fake.confirmCalls.get()).isZero();
        // Reading the (empty) list stays public and works.
        mvc.perform(get("/api/broadcasts/{id}/donations", broadcastId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(0));
    }

    @Test
    void webhookIsOffByDefault() throws Exception {
        mvc.perform(post("/api/payments/toss/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"paymentKey\":\"k\"}}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        assertThat(fake.getCalls.get()).isZero();
    }
}
