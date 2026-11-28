package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(String orderId);

    Optional<Payment> findByTransactionId(String transactionId);

    /** Kapital bank order id lookup (payments.order_id holds the FitNest id for KAPITAL). */
    Optional<Payment> findByProviderOrderId(String providerOrderId);

    /** Row lock so callback and reconciliation cannot finalize the same Kapital payment twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.providerOrderId = :providerOrderId")
    Optional<Payment> findByProviderOrderIdForUpdate(@Param("providerOrderId") String providerOrderId);

    List<Payment> findByTransactionIdIn(java.util.Collection<String> transactionIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.transactionId = :transactionId")
    Optional<Payment> findByTransactionIdForUpdate(@Param("transactionId") String transactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    /** Stale non-terminal payments for the reconciliation job. */
    List<Payment> findByProviderAndStatusInAndCreatedDateBefore(
            String provider, java.util.Collection<String> statuses, java.time.LocalDateTime before);

    /** Recent unfinished payments of one user — used for init idempotency/recovery. */
    List<Payment> findTop20ByProviderAndUserIdAndStatusInAndCreatedDateAfterOrderByCreatedDateDesc(
            String provider, Long userId, java.util.Collection<String> statuses, java.time.LocalDateTime after);

    List<Payment> findAllByUserId(Long userId);
    List<Payment> findByStatusAndCreatedDateBetween(String status, java.time.LocalDateTime start, java.time.LocalDateTime end);

    /** Dashboard income: SUCCESS cash rails only (excludes FitCoin full checkout). */
    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
            WHERE p.status = :status
              AND p.createdDate BETWEEN :start AND :end
              AND (p.provider IS NULL OR p.provider <> 'COIN')
              AND (p.type IS NULL OR p.type <> 'FULL_COIN_PAYMENT')
            """)
    Double sumIncomeExcludingFitCoin(
            @Param("status") String status,
            @Param("start") java.time.LocalDateTime start,
            @Param("end") java.time.LocalDateTime end);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
            WHERE p.status = :status AND p.createdDate BETWEEN :start AND :end
            """)
    Double sumAmountByStatusAndCreatedDateBetween(
            @Param("status") String status,
            @Param("start") java.time.LocalDateTime start,
            @Param("end") java.time.LocalDateTime end);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
            WHERE p.status = 'SUCCESS'
              AND p.provider IN :providers
              AND p.createdDate BETWEEN :start AND :end
            """)
    Double sumAmountByProvidersAndCreatedDateBetween(
            @Param("providers") java.util.Collection<String> providers,
            @Param("start") java.time.LocalDateTime start,
            @Param("end") java.time.LocalDateTime end);

    /** Dashboard sparkline: daily income excluding FitCoin. */
    @Query("""
            SELECT CAST(p.createdDate AS LocalDate), COALESCE(SUM(p.amount), 0)
            FROM Payment p
            WHERE p.status = 'SUCCESS'
              AND p.createdDate BETWEEN :start AND :end
              AND (p.provider IS NULL OR p.provider <> 'COIN')
              AND (p.type IS NULL OR p.type <> 'FULL_COIN_PAYMENT')
            GROUP BY CAST(p.createdDate AS LocalDate)
            ORDER BY CAST(p.createdDate AS LocalDate)
            """)
    java.util.List<Object[]> sumIncomeByDayExcludingFitCoin(
            @Param("start") java.time.LocalDateTime start,
            @Param("end") java.time.LocalDateTime end);

    /** Provider-ə görə ödənişlər (məs: "ABB", "EPOINT") */
    List<Payment> findAllByProvider(String provider);

    /** Provider + status kombinasiyasına görə ödənişlər */
    List<Payment> findAllByProviderAndStatus(String provider, String status);

    /** İstifadəçi + provider kombinasiyasına görə ödənişlər */
    List<Payment> findAllByUserIdAndProvider(Long userId, String provider);

    void deleteByUserId(Long userId);
}
