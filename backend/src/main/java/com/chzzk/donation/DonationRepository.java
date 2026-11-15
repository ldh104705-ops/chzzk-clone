package com.chzzk.donation;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DonationRepository extends JpaRepository<Donation, Long> {

    Optional<Donation> findByOrderId(String orderId);

    /** SELECT ... FOR UPDATE: concurrent confirms/webhooks for one order queue up behind each other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Donation d where d.orderId = :orderId")
    Optional<Donation> findByOrderIdForUpdate(@Param("orderId") String orderId);

    /**
     * PENDING -> CONFIRMED, exactly once: the WHERE clause matches only while the row is still PENDING, so the
     * second of two racing callers updates 0 rows. Violating uq_donation_payment_key throws
     * DataIntegrityViolationException (the same payment cannot confirm two donations).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE donation SET status = 'CONFIRMED', payment_key = :paymentKey, approved_at = :approvedAt, "
            + "updated_at = now() WHERE order_id = :orderId AND status = 'PENDING'", nativeQuery = true)
    int markConfirmed(@Param("orderId") String orderId, @Param("paymentKey") String paymentKey,
                      @Param("approvedAt") Instant approvedAt);

    /** PENDING -> FAILED or CANCELED (never touches a row that already left PENDING). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE donation SET status = :status, fail_code = :code, fail_message = :message, "
            + "updated_at = now() WHERE order_id = :orderId AND status = 'PENDING'", nativeQuery = true)
    int markClosed(@Param("orderId") String orderId, @Param("status") String status, @Param("code") String code,
                   @Param("message") String message);

    @Query("select d from Donation d where d.broadcastId = :broadcastId and d.status = :status "
            + "order by d.approvedAt desc, d.id desc")
    List<Donation> findRecent(@Param("broadcastId") Long broadcastId, @Param("status") DonationStatus status,
                              Pageable pageable);

    @Query("select new com.chzzk.donation.DonationTotals(coalesce(sum(d.amount), 0L), count(d)) "
            + "from Donation d where d.broadcastId = :broadcastId and d.status = :status")
    DonationTotals totals(@Param("broadcastId") Long broadcastId, @Param("status") DonationStatus status);
}
