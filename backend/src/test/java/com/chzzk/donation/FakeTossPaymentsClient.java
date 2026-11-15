package com.chzzk.donation;

import com.chzzk.payment.TossApiException;
import com.chzzk.payment.TossPayment;
import com.chzzk.payment.TossPaymentsClient;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/** Test double for Toss: approves whatever it is asked to, counts calls, and can be told to fail or to lie. */
public class FakeTossPaymentsClient implements TossPaymentsClient {

    public final AtomicInteger confirmCalls = new AtomicInteger();
    public final AtomicInteger getCalls = new AtomicInteger();
    /** Payments Toss "knows": filled by confirm() and by register() (for webhook tests). Keyed by paymentKey. */
    private final Map<String, TossPayment> payments = new ConcurrentHashMap<>();
    /** When set, confirm() throws it instead of approving. */
    public volatile TossApiException confirmFailure;
    /** When set, confirm() answers with this transformation of the honest answer (to simulate a mismatching Toss). */
    public volatile UnaryOperator<TossPayment> confirmTamper;

    public void reset() {
        confirmCalls.set(0);
        getCalls.set(0);
        payments.clear();
        confirmFailure = null;
        confirmTamper = null;
    }

    /** Makes Toss "know" a payment (e.g. one approved while our server was down). */
    public void register(TossPayment payment) {
        payments.put(payment.paymentKey(), payment);
    }

    public static TossPayment done(String paymentKey, String orderId, long amount) {
        return new TossPayment(paymentKey, orderId, "DONE", amount, OffsetDateTime.now());
    }

    @Override
    public TossPayment confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        confirmCalls.incrementAndGet();
        if (confirmFailure != null) {
            throw confirmFailure;
        }
        TossPayment honest = done(paymentKey, orderId, amount);
        payments.put(paymentKey, honest);
        UnaryOperator<TossPayment> tamper = confirmTamper;
        return tamper == null ? honest : tamper.apply(honest);
    }

    @Override
    public TossPayment get(String paymentKey) {
        getCalls.incrementAndGet();
        TossPayment p = payments.get(paymentKey);
        if (p == null) {
            throw new TossApiException(404, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.");
        }
        return p;
    }
}
