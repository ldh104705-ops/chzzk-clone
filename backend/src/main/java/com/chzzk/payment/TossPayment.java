package com.chzzk.payment;

import java.time.OffsetDateTime;

/**
 * The parts of a Toss "Payment" object the server relies on. {@code status} is Toss's own value
 * (DONE, ABORTED, EXPIRED, CANCELED, ...); only DONE means the money was approved.
 */
public record TossPayment(String paymentKey, String orderId, String status, long totalAmount,
                          OffsetDateTime approvedAt) {

    public boolean isDone() {
        return "DONE".equals(status);
    }
}
