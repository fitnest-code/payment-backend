package az.fitnest.payment.repository;

import az.fitnest.payment.model.entity.PaymentOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PaymentOutboxEventRepository extends JpaRepository<PaymentOutboxEvent, Long> {

    /**
     * Claims a batch of due rows for this instance.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets every replica run the relay concurrently without
     * two of them ever picking up the same row, so the relay scales with the deployment instead
     * of needing a leader election.</p>
     */
    @Query(value = """
            SELECT * FROM payment_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PaymentOutboxEvent> lockPendingBatch(@Param("now") Instant now, @Param("limit") int limit);

    long countByStatus(String status);

    /** Prevents duplicate Kafka/gRPC side-effects when callbacks or status polls repeat. */
    boolean existsByAggregateIdAndEventType(String aggregateId, String eventType);

    @Modifying
    @Query("DELETE FROM PaymentOutboxEvent e WHERE e.status = 'PUBLISHED' AND e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
