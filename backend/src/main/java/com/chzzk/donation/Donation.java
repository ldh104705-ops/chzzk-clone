package com.chzzk.donation;

import com.chzzk.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One donation from a logged-in user to a broadcaster, paid through Toss Payments. Rows start PENDING; the status
 * and payment fields change ONLY through the conditional updates in {@link DonationRepository}
 * ({@code ... WHERE status = 'PENDING'}), so this entity has no setters and a state change can never be applied twice.
 */
@Entity
@Table(name = "donation")
public class Donation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false, length = 64)
    private String orderId;

    @Column(name = "payment_key", length = 200, updatable = false, insertable = false)
    private String paymentKey;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private DonationStatus status;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "donor_id", nullable = false, updatable = false)
    private User donor;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false, updatable = false)
    private User recipient;

    @Column(name = "broadcast_id", updatable = false)
    private Long broadcastId;

    @Column(length = 100, updatable = false)
    private String message;

    @Column(name = "fail_code", length = 100, updatable = false, insertable = false)
    private String failCode;

    @Column(name = "fail_message", length = 500, updatable = false, insertable = false)
    private String failMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "approved_at", updatable = false, insertable = false)
    private Instant approvedAt;

    protected Donation() {
    }

    /** A new PENDING donation. */
    public Donation(String orderId, long amount, User donor, User recipient, Long broadcastId, String message) {
        this.orderId = orderId;
        this.amount = amount;
        this.status = DonationStatus.PENDING;
        this.donor = donor;
        this.recipient = recipient;
        this.broadcastId = broadcastId;
        this.message = message;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getPaymentKey() { return paymentKey; }
    public long getAmount() { return amount; }
    public DonationStatus getStatus() { return status; }
    public User getDonor() { return donor; }
    public User getRecipient() { return recipient; }
    public Long getBroadcastId() { return broadcastId; }
    public String getMessage() { return message; }
    public String getFailCode() { return failCode; }
    public String getFailMessage() { return failMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getApprovedAt() { return approvedAt; }
}
