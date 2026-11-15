package com.chzzk.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chzzk.TestUsers;
import com.chzzk.TestUsers.Account;
import com.chzzk.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The V4 constraints are what make duplicate approvals harmless even if application code is wrong, so they are
 * tested directly with plain SQL (no service involved).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DonationConstraintTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired DonationRepository donations;
    @Autowired TestUsers users;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    Account donor;
    Account recipient;

    @BeforeEach
    void setUp() {
        donations.deleteAll();
        donor = users.register("donor");
        recipient = users.register("recipient");
    }

    private void insert(String orderId, String paymentKey, long amount, String status, long donorId, long recipientId) {
        jdbc.update("INSERT INTO donation (order_id, payment_key, amount, status, donor_id, recipient_id, approved_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? = 'CONFIRMED' THEN now() END)",
                orderId, paymentKey, amount, status, donorId, recipientId, status);
    }

    /** The conditional UPDATE is a @Modifying query, which needs a transaction (the service provides one). */
    private int confirmInTx(String orderId, String paymentKey, java.time.Instant at) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(s -> donations.markConfirmed(orderId, paymentKey, at));
    }

    private void insertValid(String orderId, String paymentKey) {
        insert(orderId, paymentKey, 5000, paymentKey == null ? "PENDING" : "CONFIRMED", donor.id(), recipient.id());
    }

    @Test
    void aSecondRowWithTheSameOrderIdIsRejected() {
        insertValid("donation_dup-order", null);
        assertThatThrownBy(() -> insertValid("donation_dup-order", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_donation_order_id");
        assertThat(donations.count()).isEqualTo(1);
    }

    @Test
    void aSecondRowWithTheSamePaymentKeyIsRejectedButManyPendingRowsWithoutOneAreFine() {
        insertValid("donation_order-1", "pay_key_same");
        assertThatThrownBy(() -> insertValid("donation_order-2", "pay_key_same"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_donation_payment_key");

        insertValid("donation_pending-1", null); // NULL payment_key: unlimited PENDING rows
        insertValid("donation_pending-2", null);
        assertThat(donations.count()).isEqualTo(3);
    }

    @Test
    void theConditionalConfirmUpdateWorksOnceAndTheSamePaymentCannotConfirmTwoOrders() {
        insertValid("donation_order-a", null);
        insertValid("donation_order-b", null);
        java.time.Instant now = java.time.Instant.now();

        assertThat(confirmInTx("donation_order-a", "pay_key_1", now)).isEqualTo(1);
        assertThat(confirmInTx("donation_order-a", "pay_key_1", now)).isZero(); // already CONFIRMED: no-op
        assertThat(confirmInTx("donation_order-a", "pay_key_2", now)).isZero(); // cannot be re-pointed
        assertThatThrownBy(() -> confirmInTx("donation_order-b", "pay_key_1", now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(donations.findByOrderId("donation_order-a").orElseThrow().getPaymentKey()).isEqualTo("pay_key_1");
    }

    @Test
    void amountStatusSelfDonationAndOrderIdFormatAreChecked() {
        assertThatThrownBy(() -> insert("donation_low", null, 999, "PENDING", donor.id(), recipient.id()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_donation_amount");
        assertThatThrownBy(() -> insert("donation_high", null, 1_000_001, "PENDING", donor.id(), recipient.id()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_donation_amount");
        assertThatThrownBy(() -> insert("donation_status", null, 5000, "REFUNDED", donor.id(), recipient.id()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_donation_status");
        assertThatThrownBy(() -> insert("donation_self", null, 5000, "PENDING", donor.id(), donor.id()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_donation_not_self");
        assertThatThrownBy(() -> insert("bad id", null, 5000, "PENDING", donor.id(), recipient.id()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_donation_order_id");
        assertThat(donations.count()).isZero();
    }

    @Test
    void aConfirmedRowMustCarryItsPaymentKey() {
        assertThatThrownBy(() -> insert("donation_no-key", null, 5000, "CONFIRMED", donor.id(), recipient.id()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("chk_donation_confirmed_shape");
    }
}
