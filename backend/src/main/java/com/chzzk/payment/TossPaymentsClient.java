package com.chzzk.payment;

/**
 * The two Toss Payments server APIs this project uses. An interface so tests can plug in a fake and so the
 * donation logic never depends on HTTP details.
 */
public interface TossPaymentsClient {

    /**
     * POST /v1/payments/confirm: approves a payment the buyer authenticated in the payment window.
     * {@code idempotencyKey} is sent as the Idempotency-Key header, so a retry returns Toss's first answer
     * instead of approving twice.
     */
    TossPayment confirm(String paymentKey, String orderId, long amount, String idempotencyKey);

    /** GET /v1/payments/{paymentKey}: the payment as Toss itself currently knows it. */
    TossPayment get(String paymentKey);
}
