package com.chzzk.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chzzk.TestUsers;
import com.chzzk.TestUsers.Account;
import com.chzzk.TestcontainersConfiguration;
import com.chzzk.broadcast.BroadcastRepository;
import com.chzzk.payment.TossApiException;
import com.chzzk.payment.TossPayment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The donation flow against a real PostgreSQL, with a fake Toss client (no network). Keys below are obvious
 * placeholders with the "test_" prefix that PaymentSettings requires; they are not real Toss keys.
 */
@SpringBootTest(properties = {
        "app.toss.client-key=test_gck_placeholder_for_unit_tests",
        "app.toss.secret-key=test_gsk_placeholder_for_unit_tests",
        "app.toss.webhook-enabled=true"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeTossConfig.class})
class DonationApiTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired BroadcastRepository broadcasts;
    @Autowired DonationRepository donations;
    @Autowired FakeTossPaymentsClient fake;
    @Autowired TestUsers users;
    @Autowired JdbcTemplate jdbc;

    Account owner;
    Account donor;
    Account other;
    long broadcastId;

    @BeforeEach
    void setUp() throws Exception {
        donations.deleteAll();
        broadcasts.deleteAll();
        fake.reset();
        owner = users.register("owner");
        donor = users.register("donor");
        other = users.register("other");
        broadcastId = createBroadcast(owner);
    }

    // ------------------------------------------------------------------ helpers

    private long createBroadcast(Account by) throws Exception {
        String location = mvc.perform(multipart("/api/broadcasts/recorded").header("Authorization", by.bearer())
                        .param("title", "후원 방송").param("videoUrl", "https://example.com/a.mp4"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private ResultActions postJson(Account by, String url, Map<String, ?> body) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(by == null ? request : request.header("Authorization", by.bearer()));
    }

    private Map<String, Object> createBody(long onBroadcast, Object amount, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("broadcastId", onBroadcast);
        body.put("amount", amount);
        if (message != null) {
            body.put("message", message);
        }
        return body;
    }

    private ResultActions create(Account by, Object amount, String message) throws Exception {
        return postJson(by, "/api/donations", createBody(broadcastId, amount, message));
    }

    /** Creates a PENDING donation as {@code by} and returns its order id. */
    private String order(Account by, long amount, String message) throws Exception {
        String body = create(by, amount, message).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("orderId").asString();
    }

    private ResultActions confirm(Account by, String paymentKey, String orderId, Object amount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentKey", paymentKey);
        body.put("orderId", orderId);
        body.put("amount", amount);
        return postJson(by, "/api/donations/confirm", body);
    }

    private ResultActions fail(Account by, String orderId, String code, String message) throws Exception {
        return postJson(by, "/api/donations/" + orderId + "/fail", Map.of("code", code, "message", message));
    }

    private Donation row(String orderId) {
        return donations.findByOrderId(orderId).orElseThrow();
    }

    private JsonNode listBody() throws Exception {
        String body = mvc.perform(get("/api/broadcasts/{id}/donations", broadcastId)) // no Authorization header: public
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private ResultActions webhook(Map<String, ?> body) throws Exception {
        return mvc.perform(post("/api/payments/toss/webhook").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    // ------------------------------------------------------------------ config

    @Test
    void configIsEnabledAndExposesOnlyTheClientKey() throws Exception {
        String body = mvc.perform(get("/api/payments/config")) // public
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.clientKey").value("test_gck_placeholder_for_unit_tests"))
                .andExpect(jsonPath("$.minAmount").value(1000))
                .andExpect(jsonPath("$.maxAmount").value(1000000))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("gsk_").doesNotContain("secret");
    }

    // ------------------------------------------------------------------ create

    @Test
    void creatingADonationRequiresLogin() throws Exception {
        create(null, 5000, null).andExpect(status().isUnauthorized());
        assertThat(donations.count()).isZero();
    }

    @Test
    void createStoresAPendingDonationToTheBroadcastOwner() throws Exception {
        create(donor, 5000, "  힘내세요  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.broadcastId").value(broadcastId))
                .andExpect(jsonPath("$.recipientNickname").value("owner"))
                .andExpect(jsonPath("$.orderName").value("후원: owner"))
                .andExpect(jsonPath("$.successUrl").value("http://localhost:5173/donations/success"))
                .andExpect(jsonPath("$.failUrl").value("http://localhost:5173/donations/fail"));

        Donation d = donations.findAll().getFirst();
        assertThat(d.getStatus()).isEqualTo(DonationStatus.PENDING);
        assertThat(d.getOrderId()).matches("^donation_[0-9a-f-]{36}$");
        assertThat(d.getDonor().getId()).isEqualTo(donor.id());
        assertThat(d.getRecipient().getId()).isEqualTo(owner.id()); // the owner, not something the client chose
        assertThat(d.getMessage()).isEqualTo("힘내세요");
        assertThat(d.getPaymentKey()).isNull();
    }

    @Test
    void amountAndMessageAreValidated() throws Exception {
        for (Object bad : new Object[] {999, 0, -1000, 1_000_001, 1500.5}) {
            create(donor, bad, null).andExpect(status().isBadRequest());
        }
        create(donor, null, null).andExpect(status().isBadRequest());
        create(donor, 1000, "x".repeat(101)).andExpect(status().isBadRequest());
        postJson(donor, "/api/donations", Map.of("amount", 5000)).andExpect(status().isBadRequest()); // no broadcastId
        postJson(donor, "/api/donations", createBody(broadcastId + 1000, 5000, null)).andExpect(status().isNotFound());
        assertThat(donations.count()).isZero();

        create(donor, 1000, "x".repeat(100)).andExpect(status().isCreated()); // limits themselves are fine
        create(donor, 1_000_000, null).andExpect(status().isCreated());
        assertThat(donations.count()).isEqualTo(2);
    }

    @Test
    void donatingToYourOwnBroadcastIsRefused() throws Exception {
        create(owner, 5000, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("자신의 방송에는 후원할 수 없습니다."));
        assertThat(donations.count()).isZero();
    }

    // ------------------------------------------------------------------ confirm

    @Test
    void confirmVerifiesWithTossThenConfirmsAndTheDonationAppearsInTheListAndTotal() throws Exception {
        String orderId = order(donor, 5000, "응원합니다");

        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.alreadyConfirmed").value(false))
                .andExpect(jsonPath("$.broadcastId").value(broadcastId));

        assertThat(fake.confirmCalls.get()).isEqualTo(1);
        Donation d = row(orderId);
        assertThat(d.getStatus()).isEqualTo(DonationStatus.CONFIRMED);
        assertThat(d.getPaymentKey()).isEqualTo("pay_key_1");
        assertThat(d.getApprovedAt()).isNotNull();

        mvc.perform(get("/api/broadcasts/{id}/donations", broadcastId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].donorNickname").value("donor"))
                .andExpect(jsonPath("$.items[0].amount").value(5000))
                .andExpect(jsonPath("$.items[0].message").value("응원합니다"))
                .andExpect(jsonPath("$.totalAmount").value(5000))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void pendingFailedAndCanceledDonationsDoNotCountInTheList() throws Exception {
        order(donor, 3000, null); // stays PENDING
        String failed = order(donor, 4000, null);
        fail(donor, failed, "REJECT_CARD_COMPANY", "카드사 거절").andExpect(status().isOk());
        String confirmed = order(donor, 7000, null);
        confirm(donor, "pay_key_ok", confirmed, 7000).andExpect(status().isOk());

        JsonNode list = listBody();
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("totalAmount").asLong()).isEqualTo(7000);
        assertThat(list.get("count").asLong()).isEqualTo(1);
    }

    @Test
    void listIsNewestFirstWithATotalCoveringEverythingAndUnknownBroadcastIs404() throws Exception {
        for (int i = 1; i <= 3; i++) {
            confirm(donor, "pay_key_" + i, order(donor, 1000L * i, "후원 " + i), 1000L * i).andExpect(status().isOk());
            Thread.sleep(5);
        }
        mvc.perform(get("/api/broadcasts/{id}/donations", broadcastId).param("limit", "2"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].message").value("후원 3"))
                .andExpect(jsonPath("$.items[1].message").value("후원 2"))
                .andExpect(jsonPath("$.totalAmount").value(6000)) // the total ignores the limit
                .andExpect(jsonPath("$.count").value(3));
        mvc.perform(get("/api/broadcasts/{id}/donations", broadcastId + 1000)).andExpect(status().isNotFound());
    }

    @Test
    void amountMismatchIsRejectedBeforeTossIsAskedAndNothingIsConfirmed() throws Exception {
        String orderId = order(donor, 5000, null);

        confirm(donor, "pay_key_1", orderId, 100).andExpect(status().isBadRequest());
        confirm(donor, "pay_key_1", orderId, null).andExpect(status().isBadRequest());

        assertThat(fake.confirmCalls.get()).isZero();
        assertThat(row(orderId).getStatus()).isEqualTo(DonationStatus.PENDING);
        assertThat(listBody().get("totalAmount").asLong()).isZero();

        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isOk()); // the honest request still works
    }

    @Test
    void anotherUsersOrderCannotBeConfirmedAndAnonymousGets401() throws Exception {
        String orderId = order(donor, 5000, null);

        confirm(other, "pay_key_1", orderId, 5000).andExpect(status().isForbidden());
        confirm(owner, "pay_key_1", orderId, 5000).andExpect(status().isForbidden()); // not even the recipient
        confirm(null, "pay_key_1", orderId, 5000).andExpect(status().isUnauthorized());
        confirm(donor, "pay_key_1", "donation_does-not-exist", 5000).andExpect(status().isNotFound());
        confirm(donor, "has spaces", orderId, 5000).andExpect(status().isBadRequest());
        confirm(donor, "pay_key_1", "bad id!", 5000).andExpect(status().isBadRequest());

        assertThat(fake.confirmCalls.get()).isZero();
        assertThat(row(orderId).getStatus()).isEqualTo(DonationStatus.PENDING);
    }

    @Test
    void tossRejectingThePaymentMarksItFailedAndItIsNotCountedOrRetryable() throws Exception {
        String orderId = order(donor, 5000, null);
        fake.confirmFailure = new TossApiException(400, "REJECT_CARD_COMPANY", "카드사에서 승인을 거절했습니다.");

        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("카드사에서 승인을 거절했습니다.")));

        Donation d = row(orderId);
        assertThat(d.getStatus()).isEqualTo(DonationStatus.FAILED);
        assertThat(d.getFailCode()).isEqualTo("REJECT_CARD_COMPANY");
        assertThat(d.getPaymentKey()).isNull();
        assertThat(listBody().get("totalAmount").asLong()).isZero();

        fake.confirmFailure = null;
        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isConflict()); // final state: start a new donation
        assertThat(fake.confirmCalls.get()).isEqualTo(1);
    }

    @Test
    void tossBeingUnreachableKeepsTheOrderPendingSoTheSameConfirmCanBeRepeated() throws Exception {
        String orderId = order(donor, 5000, null);
        fake.confirmFailure = new TossApiException("timeout", new RuntimeException("timeout"));

        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isBadGateway());
        assertThat(row(orderId).getStatus()).isEqualTo(DonationStatus.PENDING);

        fake.confirmFailure = null;
        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(listBody().get("totalAmount").asLong()).isEqualTo(5000);
    }

    @Test
    void tossAnswerThatDoesNotMatchTheOrderIsNeverConfirmed() throws Exception {
        String wrongAmount = order(donor, 5000, null);
        fake.confirmTamper = p -> new TossPayment(p.paymentKey(), p.orderId(), "DONE", 100, p.approvedAt());
        confirm(donor, "pay_key_1", wrongAmount, 5000).andExpect(status().isBadGateway());
        assertThat(row(wrongAmount).getStatus()).isEqualTo(DonationStatus.FAILED);
        assertThat(row(wrongAmount).getFailCode()).isEqualTo("VERIFICATION_FAILED");

        String wrongOrder = order(donor, 5000, null);
        fake.confirmTamper = p -> new TossPayment(p.paymentKey(), "donation_some-other-order", "DONE", p.totalAmount(), p.approvedAt());
        confirm(donor, "pay_key_2", wrongOrder, 5000).andExpect(status().isBadGateway());
        assertThat(row(wrongOrder).getStatus()).isEqualTo(DonationStatus.FAILED);

        String notDone = order(donor, 5000, null);
        fake.confirmTamper = p -> new TossPayment(p.paymentKey(), p.orderId(), "WAITING_FOR_DEPOSIT", p.totalAmount(), null);
        confirm(donor, "pay_key_3", notDone, 5000).andExpect(status().isBadGateway());
        assertThat(row(notDone).getStatus()).isEqualTo(DonationStatus.FAILED);

        assertThat(listBody().get("totalAmount").asLong()).isZero();
    }

    @Test
    void ifTheRecipientNoLongerOwnsTheBroadcastTheDonationIsNotConfirmed() throws Exception {
        String orderId = order(donor, 5000, null);
        jdbc.update("UPDATE broadcast SET owner_id = ? WHERE id = ?", other.id(), broadcastId); // ownership moved

        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isConflict());

        assertThat(fake.confirmCalls.get()).isZero(); // never asked Toss to take the money
        assertThat(row(orderId).getStatus()).isEqualTo(DonationStatus.FAILED);
        assertThat(row(orderId).getFailCode()).isEqualTo("RECIPIENT_CHANGED");
    }

    @Test
    void aDeletedBroadcastCannotBeConfirmedButConfirmedDonationsSurviveItsDeletion() throws Exception {
        String confirmed = order(donor, 5000, null);
        confirm(donor, "pay_key_1", confirmed, 5000).andExpect(status().isOk());
        String pending = order(donor, 6000, null);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/broadcasts/{id}", broadcastId)
                .header("Authorization", owner.bearer())).andExpect(status().isNoContent());

        assertThat(row(confirmed).getStatus()).isEqualTo(DonationStatus.CONFIRMED); // the money was received
        assertThat(row(confirmed).getBroadcastId()).isNull();
        confirm(donor, "pay_key_2", pending, 6000).andExpect(status().isConflict());
        assertThat(row(pending).getStatus()).isEqualTo(DonationStatus.FAILED);
    }

    // ------------------------------------------------------------------ duplicates

    @Test
    void repeatingTheSameConfirmReturnsTheExistingResultWithoutCallingTossOrCountingAgain() throws Exception {
        String orderId = order(donor, 5000, null);
        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isOk()).andExpect(jsonPath("$.alreadyConfirmed").value(false));

        for (int i = 0; i < 3; i++) { // e.g. the user refreshes the success page
            confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.alreadyConfirmed").value(true));
        }

        assertThat(fake.confirmCalls.get()).isEqualTo(1);
        JsonNode list = listBody();
        assertThat(list.get("totalAmount").asLong()).isEqualTo(5000);
        assertThat(list.get("count").asLong()).isEqualTo(1);
        assertThat(donations.count()).isEqualTo(1);
    }

    @Test
    void aDifferentPaymentKeyForAnAlreadyConfirmedOrderIsAConflict() throws Exception {
        String orderId = order(donor, 5000, null);
        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isOk());

        confirm(donor, "pay_key_OTHER", orderId, 5000).andExpect(status().isConflict());

        assertThat(fake.confirmCalls.get()).isEqualTo(1);
        assertThat(row(orderId).getPaymentKey()).isEqualTo("pay_key_1");
        assertThat(listBody().get("totalAmount").asLong()).isEqualTo(5000);
    }

    @Test
    void onePaymentCannotConfirmTwoDonationsTheUniqueConstraintBecomesA409() throws Exception {
        String first = order(donor, 5000, null);
        String second = order(donor, 5000, null);
        confirm(donor, "pay_key_shared", first, 5000).andExpect(status().isOk());

        // A buggy/malicious Toss answer "approves" the second order with the same payment key.
        confirm(donor, "pay_key_shared", second, 5000).andExpect(status().isConflict());

        assertThat(row(second).getStatus()).isEqualTo(DonationStatus.PENDING);
        assertThat(listBody().get("totalAmount").asLong()).isEqualTo(5000);
        assertThat(listBody().get("count").asLong()).isEqualTo(1);
    }

    @Test
    void fiveConcurrentConfirmsCreditExactlyOnce() throws Exception {
        String orderId = order(donor, 5000, null);
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<MvcResult> task = () -> {
                    go.await();
                    return confirm(donor, "pay_key_race", orderId, 5000).andReturn();
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            int firstTimers = 0;
            for (Future<MvcResult> f : results) {
                MvcResult r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.getResponse().getStatus()).isEqualTo(200);
                JsonNode body = json.readTree(r.getResponse().getContentAsString());
                assertThat(body.get("status").asString()).isEqualTo("CONFIRMED");
                if (!body.get("alreadyConfirmed").asBoolean()) {
                    firstTimers++;
                }
            }
            assertThat(firstTimers).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(fake.confirmCalls.get()).isEqualTo(1); // the row lock queued the others behind the first
        JsonNode list = listBody();
        assertThat(list.get("totalAmount").asLong()).isEqualTo(5000);
        assertThat(list.get("count").asLong()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ fail / cancel

    @Test
    void closingThePaymentWindowMarksTheOrderCanceled() throws Exception {
        for (String code : new String[] {"USER_CANCEL", "PAY_PROCESS_CANCELED"}) {
            String orderId = order(donor, 5000, null);
            fail(donor, orderId, code, "사용자가 결제를 취소했습니다.").andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELED"));
            assertThat(row(orderId).getStatus()).isEqualTo(DonationStatus.CANCELED);
            assertThat(row(orderId).getFailCode()).isEqualTo(code);
            confirm(donor, "pay_key_x", orderId, 5000).andExpect(status().isConflict());
        }
        assertThat(fake.confirmCalls.get()).isZero();
    }

    @Test
    void otherFailureCodesMarkTheOrderFailed() throws Exception {
        String orderId = order(donor, 5000, null);
        fail(donor, orderId, "REJECT_CARD_COMPANY", "카드사 거절").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(row(orderId).getFailCode()).isEqualTo("REJECT_CARD_COMPANY");
        assertThat(row(orderId).getFailMessage()).isEqualTo("카드사 거절");
    }

    @Test
    void failEndpointCannotUndoAConfirmedDonationAndIsLimitedToTheDonor() throws Exception {
        String orderId = order(donor, 5000, null);
        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isOk());

        fail(donor, orderId, "USER_CANCEL", "취소").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED")); // unchanged
        assertThat(row(orderId).getStatus()).isEqualTo(DonationStatus.CONFIRMED);

        String pending = order(donor, 3000, null);
        fail(other, pending, "USER_CANCEL", "취소").andExpect(status().isForbidden());
        fail(null, pending, "USER_CANCEL", "취소").andExpect(status().isUnauthorized());
        fail(donor, "donation_does-not-exist", "USER_CANCEL", "취소").andExpect(status().isNotFound());
        assertThat(row(pending).getStatus()).isEqualTo(DonationStatus.PENDING);
    }

    // ------------------------------------------------------------------ webhook

    @Test
    void webhookConfirmsAPaymentThatTossApprovedButWeNeverRecorded() throws Exception {
        String orderId = order(donor, 5000, null);
        fake.register(FakeTossPaymentsClient.done("pay_key_hook", orderId, 5000)); // approved at Toss, our confirm never came

        webhook(Map.of("eventType", "PAYMENT_STATUS_CHANGED", "data", Map.of("paymentKey", "pay_key_hook")))
                .andExpect(status().isOk());

        assertThat(row(orderId).getStatus()).isEqualTo(DonationStatus.CONFIRMED);
        assertThat(row(orderId).getPaymentKey()).isEqualTo("pay_key_hook");
        assertThat(fake.confirmCalls.get()).isZero(); // Toss already approved it; the webhook only re-reads
        assertThat(fake.getCalls.get()).isEqualTo(1);
        assertThat(listBody().get("totalAmount").asLong()).isEqualTo(5000);
    }

    @Test
    void duplicateWebhooksAndWebhooksAfterTheSuccessPageChangeNothing() throws Exception {
        String orderId = order(donor, 5000, null);
        confirm(donor, "pay_key_1", orderId, 5000).andExpect(status().isOk());

        for (int i = 0; i < 3; i++) {
            webhook(Map.of("eventType", "PAYMENT_STATUS_CHANGED", "data", Map.of("paymentKey", "pay_key_1")))
                    .andExpect(status().isOk());
        }

        assertThat(fake.confirmCalls.get()).isEqualTo(1);
        JsonNode list = listBody();
        assertThat(list.get("totalAmount").asLong()).isEqualTo(5000);
        assertThat(list.get("count").asLong()).isEqualTo(1);
    }

    @Test
    void theWebhookBodyIsNotTrustedOnlyTossAnswerCounts() throws Exception {
        String orderId = order(donor, 5000, null);

        // The body claims a finished payment, but Toss has never heard of this paymentKey.
        webhook(Map.of("eventType", "PAYMENT_STATUS_CHANGED",
                "data", Map.of("paymentKey", "pay_key_forged", "orderId", orderId, "status", "DONE", "totalAmount", 5000)))
                .andExpect(status().isOk());
        assertThat(row(orderId).getStatus()).isEqualTo(DonationStatus.PENDING);

        // Toss knows the payment but says it was aborted: not confirmed either (and the order is closed).
        fake.register(new TossPayment("pay_key_aborted", orderId, "ABORTED", 5000, null));
        webhook(Map.of("data", Map.of("paymentKey", "pay_key_aborted", "status", "DONE"))).andExpect(status().isOk());
        assertThat(row(orderId).getStatus()).isEqualTo(DonationStatus.FAILED);

        // A real DONE payment for a different amount than the order is refused as well.
        String second = order(donor, 5000, null);
        fake.register(FakeTossPaymentsClient.done("pay_key_wrong_amount", second, 100));
        webhook(Map.of("data", Map.of("paymentKey", "pay_key_wrong_amount"))).andExpect(status().isOk());
        assertThat(row(second).getStatus()).isEqualTo(DonationStatus.FAILED);

        assertThat(listBody().get("totalAmount").asLong()).isZero();
    }

    @Test
    void webhookWithoutAPaymentKeyIsAcknowledgedAndIgnored() throws Exception {
        webhook(Map.of("eventType", "DEPOSIT_CALLBACK", "orderId", "x")).andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(false));
        assertThat(fake.getCalls.get()).isZero();
    }
}
