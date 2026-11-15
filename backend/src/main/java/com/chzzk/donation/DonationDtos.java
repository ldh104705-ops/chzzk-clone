package com.chzzk.donation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Request/response bodies of the donation API. */
public final class DonationDtos {

    private DonationDtos() {
    }

    /** amount is a BigDecimal only so that "1500.5" can be rejected with a clear message instead of rounded. */
    public record CreateRequest(Long broadcastId, BigDecimal amount, String message) {
    }

    public record ConfirmRequest(String paymentKey, String orderId, Long amount) {
    }

    public record FailRequest(String code, String message) {
    }

    /** What the browser needs to open the Toss payment window. Nothing here is a secret. */
    public record OrderResponse(String orderId, long amount, String orderName, String customerKey, String successUrl,
                                String failUrl, long broadcastId, String recipientNickname) {
    }

    /** {@code alreadyConfirmed} is true when this call found the donation already confirmed (a repeated notice). */
    public record DonationResponse(String orderId, DonationStatus status, long amount, Long broadcastId,
                                   String recipientNickname, String message, Instant approvedAt,
                                   boolean alreadyConfirmed) {

        static DonationResponse of(Donation d, boolean alreadyConfirmed) {
            return new DonationResponse(d.getOrderId(), d.getStatus(), d.getAmount(), d.getBroadcastId(),
                    d.getRecipient().getNickname(), d.getMessage(), d.getApprovedAt(), alreadyConfirmed);
        }
    }

    public record ListItem(long id, String donorNickname, long amount, String message, Instant approvedAt) {

        static ListItem from(Donation d) {
            return new ListItem(d.getId(), d.getDonor().getNickname(), d.getAmount(), d.getMessage(), d.getApprovedAt());
        }
    }

    /** CONFIRMED donations only. {@code totalAmount}/{@code count} cover all of them, not just the shown items. */
    public record ListResponse(List<ListItem> items, long totalAmount, long count) {
    }
}
