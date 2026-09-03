package az.fitnest.payment.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Transactional outbox row.
 *
 * <p>Written in the same database transaction that mutates a {@link Payment}, so an event can
 * never disagree with the payment state that produced it. A background relay delivers the row
 * afterwards (to Kafka, or to order-backend over gRPC) and retries with backoff until it
 * succeeds — which is what previously went missing when a post-payment side effect failed.</p>
 */
@Entity
@Table(name = "payment_outbox", indexes = {
        @Index(name = "idx_outbox_dispatch", columnList = "status, next_attempt_at"),
        @Index(name = "idx_outbox_aggregate", columnList = "aggregate_id"),
        @Index(name = "idx_outbox_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentOutboxEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DEAD = "DEAD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key the event belongs to — the payment's orderId. Also used as the Kafka key. */
    @Column(name = "aggregate_id", length = 128)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** Destination topic for Kafka-bound events; null for events delivered another way. */
    @Column(name = "topic", length = 128)
    private String topic;

    @Column(name = "payload", columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        if (status == null) {
            status = STATUS_PENDING;
        }
    }
}
