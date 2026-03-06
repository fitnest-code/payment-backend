package az.fitnest.payment.repository;
import az.fitnest.payment.model.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    Optional<IdempotencyKey> findByIdempotencyKey(String idempotencyKey);

    Optional<IdempotencyKey> findByPaymentId(Long paymentId);

    List<IdempotencyKey> findAllByPaymentId(Long paymentId);

    @Modifying
    @Query("DELETE FROM IdempotencyKey i WHERE i.expiresAt < :now")
    int deleteExpiredKeys(Instant now);

    @Query("SELECT COUNT(i) FROM IdempotencyKey i WHERE i.expiresAt > :now")
    long countActiveKeys(Instant now);
}
