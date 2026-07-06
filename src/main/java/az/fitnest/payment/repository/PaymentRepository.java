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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.transactionId = :transactionId")
    Optional<Payment> findByTransactionIdForUpdate(@Param("transactionId") String transactionId);

    List<Payment> findAllByUserId(Long userId);
    List<Payment> findByStatusAndCreatedDateBetween(String status, java.time.LocalDateTime start, java.time.LocalDateTime end);

    /** Provider-ə görə ödənişlər (məs: "ABB", "EPOINT") */
    List<Payment> findAllByProvider(String provider);

    /** Provider + status kombinasiyasına görə ödənişlər */
    List<Payment> findAllByProviderAndStatus(String provider, String status);

    /** İstifadəçi + provider kombinasiyasına görə ödənişlər */
    List<Payment> findAllByUserIdAndProvider(Long userId, String provider);

    void deleteByUserId(Long userId);
}
