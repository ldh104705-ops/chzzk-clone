package com.chzzk.donation;

import com.chzzk.auth.User;
import com.chzzk.auth.UserRepository;
import com.chzzk.broadcast.Broadcast;
import com.chzzk.broadcast.BroadcastRepository;
import com.chzzk.broadcast.BroadcastService;
import com.chzzk.common.BadGatewayException;
import com.chzzk.common.ConflictException;
import com.chzzk.common.ForbiddenException;
import com.chzzk.common.InvalidRequestException;
import com.chzzk.common.NotFoundException;
import com.chzzk.common.UnauthorizedException;
import com.chzzk.donation.DonationDtos.ConfirmRequest;
import com.chzzk.donation.DonationDtos.CreateRequest;
import com.chzzk.donation.DonationDtos.DonationResponse;
import com.chzzk.donation.DonationDtos.FailRequest;
import com.chzzk.donation.DonationDtos.ListItem;
import com.chzzk.donation.DonationDtos.ListResponse;
import com.chzzk.donation.DonationDtos.OrderResponse;
import com.chzzk.payment.PaymentSettings;
import com.chzzk.payment.TossApiException;
import com.chzzk.payment.TossPayment;
import com.chzzk.payment.TossPaymentsClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Donation lifecycle. The rule that matters: a donation becomes CONFIRMED only here, on the server, after Toss itself
 * answered DONE for exactly this order, amount and payment. Nothing the browser says (success page, query string)
 * is believed. Repeats are safe because every state change is a conditional UPDATE ... WHERE status = 'PENDING' made
 * while holding the row lock, and the unique constraints on order_id / payment_key are the last line of defence.
 */
@Service
public class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);

    static final String ORDER_ID_PREFIX = "donation_";
    static final int DEFAULT_LIST_SIZE = 30;
    static final int MAX_LIST_SIZE = 100;
    private static final Pattern ORDER_ID = Pattern.compile("^[A-Za-z0-9_=-]{6,64}$");
    /** paymentKey is an opaque string of at most 200 characters; only reject whitespace/non-ASCII, never parse it. */
    private static final Pattern PAYMENT_KEY = Pattern.compile("^[\\x21-\\x7E]{1,200}$");
    /** Toss reports these when the buyer closes or cancels the payment window. */
    private static final Set<String> CANCEL_CODES = Set.of("PAY_PROCESS_CANCELED", "USER_CANCEL");

    private final DonationRepository donations;
    private final UserRepository users;
    private final BroadcastService broadcasts;
    private final BroadcastRepository broadcastRepository;
    private final TossPaymentsClient toss;
    private final PaymentSettings settings;
    private final TransactionTemplate tx;

    public DonationService(DonationRepository donations, UserRepository users, BroadcastService broadcasts,
                           BroadcastRepository broadcastRepository, TossPaymentsClient toss, PaymentSettings settings,
                           PlatformTransactionManager transactionManager) {
        this.donations = donations;
        this.users = users;
        this.broadcasts = broadcasts;
        this.broadcastRepository = broadcastRepository;
        this.toss = toss;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** What happened inside a confirm transaction; {@code error} is thrown only AFTER the transaction committed. */
    private record Outcome(Donation donation, boolean duplicate, RuntimeException error) {
        static Outcome ok(Donation d, boolean duplicate) {
            return new Outcome(d, duplicate, null);
        }

        static Outcome error(RuntimeException e) {
            return new Outcome(null, false, e);
        }
    }

    // ---------------------------------------------------------------- 1. create the order

    /**
     * Step 2 of the flow: records a PENDING donation. The donor is the logged-in user, the recipient is whoever owns
     * the broadcast right now; the request can name neither. Self-donation is refused (policy, also a DB CHECK).
     */
    @Transactional
    public OrderResponse create(long donorId, CreateRequest request) {
        settings.requireEnabled();
        if (request == null || request.broadcastId() == null) {
            throw new InvalidRequestException("후원할 방송을 선택해 주세요.");
        }
        long amount = validateAmount(request.amount());
        String message = validateMessage(request.message());

        Broadcast broadcast = broadcasts.get(request.broadcastId());
        User recipient = broadcast.getOwner();
        if (recipient == null) {
            throw new InvalidRequestException("방송자 정보가 없는 방송에는 후원할 수 없습니다.");
        }
        if (recipient.getId().equals(donorId)) {
            throw new InvalidRequestException("자신의 방송에는 후원할 수 없습니다.");
        }
        User donor = users.findById(donorId).orElseThrow(() -> new UnauthorizedException("존재하지 않는 사용자입니다."));

        String orderId = ORDER_ID_PREFIX + UUID.randomUUID();
        donations.save(new Donation(orderId, amount, donor, recipient, broadcast.getId(), message));

        String orderName = "후원: " + recipient.getNickname(); // Toss limits orderName to 100 characters
        // customerKey: Toss wants 22+ unguessable characters with a special character; a fresh UUID satisfies that
        // and, unlike a user id or e-mail, reveals nothing.
        String customerKey = "ck_" + UUID.randomUUID();
        return new OrderResponse(orderId, amount, orderName, customerKey,
                settings.frontendBaseUrl() + "/donations/success", settings.frontendBaseUrl() + "/donations/fail",
                broadcast.getId(), recipient.getNickname());
    }

    // ---------------------------------------------------------------- 2. confirm (success page)

    /**
     * Steps 4: approve with Toss and confirm. Safe to call any number of times, from any number of threads.
     * Returns 200 with the donation (alreadyConfirmed=true on repeats) without calling Toss again.
     */
    public DonationResponse confirm(long userId, ConfirmRequest request) {
        settings.requireEnabled();
        if (request == null || request.amount() == null) {
            throw new InvalidRequestException("paymentKey, orderId, amount는 모두 필요합니다.");
        }
        String orderId = requireOrderId(request.orderId());
        String paymentKey = requirePaymentKey(request.paymentKey());
        long amount = request.amount();

        Outcome outcome;
        try {
            outcome = tx.execute(status -> confirmLocked(userId, orderId, paymentKey, amount));
        } catch (DataIntegrityViolationException e) {
            // uq_donation_payment_key / uq_donation_order_id said no: the transaction rolled back, look again.
            log.warn("Constraint stopped a duplicate approval for order {}", orderId);
            outcome = tx.execute(status -> afterConstraintViolation(userId, orderId, paymentKey));
        }
        if (outcome.error() != null) {
            throw outcome.error();
        }
        return DonationResponse.of(outcome.donation(), outcome.duplicate());
    }

    private Outcome confirmLocked(long userId, String orderId, String paymentKey, long amount) {
        Donation d = donations.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다."));
        if (!d.getDonor().getId().equals(userId)) {
            throw new ForbiddenException("본인의 후원 주문만 확정할 수 있습니다.");
        }
        if (d.getStatus() == DonationStatus.CONFIRMED) {
            if (paymentKey.equals(d.getPaymentKey())) {
                return Outcome.ok(d, true); // repeated notice: answer from our own record, do not call Toss again
            }
            throw new ConflictException("이미 다른 결제로 확정된 주문입니다.");
        }
        if (d.getStatus() != DonationStatus.PENDING) {
            throw new ConflictException("이미 " + (d.getStatus() == DonationStatus.CANCELED ? "취소" : "실패") + " 처리된 주문입니다.");
        }
        if (amount != d.getAmount()) {
            throw new InvalidRequestException("결제 금액이 주문 금액과 일치하지 않습니다.");
        }
        Broadcast broadcast = d.getBroadcastId() == null ? null : broadcastRepository.findById(d.getBroadcastId()).orElse(null);
        if (broadcast == null || broadcast.getOwnerId() == null || !broadcast.getOwnerId().equals(d.getRecipient().getId())) {
            donations.markClosed(orderId, "FAILED", "RECIPIENT_CHANGED", "방송이 삭제되었거나 방송자가 달라져 후원을 확정하지 않았습니다.");
            return Outcome.error(new ConflictException("방송이 삭제되었거나 방송자가 달라져 후원을 확정할 수 없습니다. 결제는 승인되지 않았습니다."));
        }

        TossPayment payment;
        try {
            // The amount sent is OUR stored amount (equal to the request's, checked above), never a client value.
            payment = toss.confirm(paymentKey, orderId, d.getAmount(), idempotencyKey(orderId));
        } catch (TossApiException e) {
            return onConfirmError(d, paymentKey, e);
        }
        return applyVerified(d, payment, paymentKey);
    }

    private Outcome onConfirmError(Donation d, String paymentKey, TossApiException e) {
        if (e.isRetryable()) {
            // No trustworthy answer (timeout, 5xx, bad key): the payment may or may not be approved. Keep PENDING;
            // the same request can simply be repeated (the Idempotency-Key makes Toss answer consistently).
            throw new BadGatewayException("결제 승인 결과를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        if ("ALREADY_PROCESSED_PAYMENT".equals(e.code())) {
            // A previous attempt got through to Toss but we never recorded it: ask Toss how the payment stands.
            try {
                return applyVerified(d, toss.get(paymentKey), paymentKey);
            } catch (TossApiException again) {
                throw new BadGatewayException("결제 승인 결과를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.");
            }
        }
        donations.markClosed(d.getOrderId(), "FAILED", e.code(), truncate(e.tossMessage(), 500));
        return Outcome.error(new InvalidRequestException("결제 승인이 거절되었습니다: " + e.tossMessage()));
    }

    /**
     * Verifies Toss's answer against OUR record and, only if everything matches, flips PENDING -> CONFIRMED.
     * The payments are TEST payments by construction: payments are enabled only with test_ keys (PaymentSettings).
     * Must run inside a transaction that holds the row lock.
     */
    private Outcome applyVerified(Donation d, TossPayment payment, String paymentKey) {
        boolean matches = payment.isDone()
                && d.getOrderId().equals(payment.orderId())
                && payment.totalAmount() == d.getAmount()
                && paymentKey.equals(payment.paymentKey());
        if (!matches) {
            log.error("Toss payment does not match donation {}: tossStatus={} tossAmount={} storedAmount={}",
                    d.getOrderId(), payment.status(), payment.totalAmount(), d.getAmount());
            donations.markClosed(d.getOrderId(), "FAILED", "VERIFICATION_FAILED",
                    "결제 승인 결과(상태·주문번호·금액)가 주문과 일치하지 않습니다.");
            return Outcome.error(new BadGatewayException("결제 승인 결과가 주문과 일치하지 않아 후원을 확정하지 않았습니다."));
        }
        Instant approvedAt = payment.approvedAt() == null ? Instant.now() : payment.approvedAt().toInstant();
        int updated = donations.markConfirmed(d.getOrderId(), paymentKey, approvedAt);
        Donation fresh = donations.findByOrderId(d.getOrderId()).orElseThrow();
        if (updated == 1) {
            return Outcome.ok(fresh, false);
        }
        if (fresh.getStatus() == DonationStatus.CONFIRMED && paymentKey.equals(fresh.getPaymentKey())) {
            return Outcome.ok(fresh, true); // somebody else confirmed it first with the same payment: idempotent
        }
        return Outcome.error(new ConflictException("이미 처리된 주문입니다."));
    }

    private Outcome afterConstraintViolation(long userId, String orderId, String paymentKey) {
        Donation d = donations.findByOrderId(orderId).orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다."));
        if (!d.getDonor().getId().equals(userId)) {
            throw new ForbiddenException("본인의 후원 주문만 확정할 수 있습니다.");
        }
        if (d.getStatus() == DonationStatus.CONFIRMED && paymentKey.equals(d.getPaymentKey())) {
            return Outcome.ok(d, true);
        }
        throw new ConflictException("이미 다른 후원에 사용된 결제입니다.");
    }

    // ---------------------------------------------------------------- 3. fail / cancel (fail page)

    /**
     * Called by the fail page. Closes a PENDING order as CANCELED (the buyer closed the window) or FAILED. An order
     * that already left PENDING is returned unchanged: a confirmed donation can never be turned into a failed one.
     */
    @Transactional
    public DonationResponse reportFailure(long userId, String orderId, FailRequest request) {
        String id = requireOrderId(orderId);
        Donation d = donations.findByOrderIdForUpdate(id).orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다."));
        if (!d.getDonor().getId().equals(userId)) {
            throw new ForbiddenException("본인의 후원 주문만 처리할 수 있습니다.");
        }
        if (d.getStatus() == DonationStatus.PENDING) {
            String code = request == null || request.code() == null || request.code().isBlank()
                    ? "UNKNOWN" : truncate(request.code().trim(), 100);
            String message = request == null ? null : truncate(request.message(), 500);
            String target = CANCEL_CODES.contains(code) ? "CANCELED" : "FAILED";
            donations.markClosed(id, target, code, message);
            d = donations.findByOrderId(id).orElseThrow();
        }
        return DonationResponse.of(d, false);
    }

    // ---------------------------------------------------------------- 4. webhook

    /**
     * Webhook path. The notification body is NOT trusted: only its paymentKey is used, to re-fetch the payment from
     * Toss, and the same verified, idempotent logic as the success page decides what to do.
     */
    public void handleWebhook(String paymentKey) {
        settings.requireEnabled();
        TossPayment payment;
        try {
            payment = toss.get(requirePaymentKey(paymentKey));
        } catch (TossApiException e) {
            if (e.isRetryable()) {
                throw new BadGatewayException("결제 조회에 실패했습니다."); // 5xx makes Toss retry the notification later
            }
            log.info("Webhook for an unknown payment ignored (Toss code {})", e.code());
            return;
        }
        try {
            tx.executeWithoutResult(status -> applyWebhook(payment));
        } catch (DataIntegrityViolationException e) {
            log.info("Webhook for order {} ignored: constraint says it is already recorded", payment.orderId());
        }
    }

    private void applyWebhook(TossPayment payment) {
        Donation d = donations.findByOrderIdForUpdate(payment.orderId()).orElse(null);
        if (d == null) {
            log.info("Webhook for an order that is not ours ignored");
            return;
        }
        if (payment.isDone()) {
            if (d.getStatus() == DonationStatus.PENDING) {
                applyVerified(d, payment, payment.paymentKey());
            } else if (d.getStatus() == DonationStatus.CONFIRMED && payment.paymentKey().equals(d.getPaymentKey())) {
                log.info("Duplicate approval notice for order {} ignored (already confirmed)", d.getOrderId());
            } else {
                log.warn("Toss reports DONE for order {} but the donation is {}: needs a human look", d.getOrderId(), d.getStatus());
            }
        } else if (("ABORTED".equals(payment.status()) || "EXPIRED".equals(payment.status()))
                && d.getStatus() == DonationStatus.PENDING) {
            donations.markClosed(d.getOrderId(), "FAILED", payment.status(), "결제가 완료되지 않았습니다(" + payment.status() + ").");
        }
    }

    // ---------------------------------------------------------------- 5. public list

    /** Confirmed donations of a broadcast, newest first, with the database-computed total (public). */
    @Transactional(readOnly = true)
    public ListResponse list(long broadcastId, Integer limit) {
        broadcasts.get(broadcastId); // 404 for an unknown broadcast
        int size = limit == null ? DEFAULT_LIST_SIZE : Math.min(Math.max(limit, 1), MAX_LIST_SIZE);
        List<ListItem> items = donations.findRecent(broadcastId, DonationStatus.CONFIRMED, PageRequest.of(0, size))
                .stream().map(ListItem::from).toList();
        DonationTotals totals = donations.totals(broadcastId, DonationStatus.CONFIRMED);
        return new ListResponse(items, totals.totalAmount(), totals.count());
    }

    // ---------------------------------------------------------------- validation helpers

    static long validateAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidRequestException("후원 금액을 입력해 주세요.");
        }
        BigDecimal normalized = amount.stripTrailingZeros();
        if (normalized.scale() > 0) {
            throw new InvalidRequestException("후원 금액은 정수(원)여야 합니다.");
        }
        if (amount.compareTo(BigDecimal.valueOf(PaymentSettings.MIN_AMOUNT)) < 0
                || amount.compareTo(BigDecimal.valueOf(PaymentSettings.MAX_AMOUNT)) > 0) {
            throw new InvalidRequestException("후원 금액은 " + String.format("%,d", PaymentSettings.MIN_AMOUNT) + "원 이상 "
                    + String.format("%,d", PaymentSettings.MAX_AMOUNT) + "원 이하여야 합니다.");
        }
        return amount.longValueExact();
    }

    static String validateMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String clean = message.trim();
        if (clean.codePointCount(0, clean.length()) > PaymentSettings.MAX_MESSAGE_LENGTH) {
            throw new InvalidRequestException("후원 메시지는 " + PaymentSettings.MAX_MESSAGE_LENGTH + "자 이하여야 합니다.");
        }
        return clean;
    }

    private static String requireOrderId(String orderId) {
        if (orderId == null || !ORDER_ID.matcher(orderId).matches()) {
            throw new InvalidRequestException("주문번호 형식이 올바르지 않습니다.");
        }
        return orderId;
    }

    private static String requirePaymentKey(String paymentKey) {
        if (paymentKey == null || !PAYMENT_KEY.matcher(paymentKey).matches()) {
            throw new InvalidRequestException("paymentKey 형식이 올바르지 않습니다.");
        }
        return paymentKey;
    }

    /** The random UUID inside the order id doubles as Toss's Idempotency-Key (UUID v4), stable per order. */
    private static String idempotencyKey(String orderId) {
        return orderId.startsWith(ORDER_ID_PREFIX) ? orderId.substring(ORDER_ID_PREFIX.length()) : orderId;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
