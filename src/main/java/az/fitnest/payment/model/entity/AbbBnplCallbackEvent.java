package az.fitnest.payment.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Durable record of every ABB BNPL callback.
 *
 * <p>Why: ABB retries a callback only twice and gives up. When a callback references an
 * order we do not know yet (response lost, race with the init TX) the old code answered
 * 400, which made ABB drop the event permanently. We now persist the event, answer 200,
 * and let the reconciliation job apply it once the order shows up.</p>
 */
@Entity
@Table(name = "abb_bnpl_callback_events", indexes = {
        @Index(name = "idx_abb_bnpl_cb_callback_id", columnList = "callback_id"),
        @Index(name = "idx_abb_bnpl_cb_order_id", columnList = "abb_order_id"),
        @Index(name = "idx_abb_bnpl_cb_processed", columnList = "processed")
})
@Getter
@Setter
@NoArgsConstructor
public class AbbBnplCallbackEvent extends BaseEntity {

    @Column(name = "callback_id", length = 128)
    private String callbackId;

    @Column(name = "abb_order_id", length = 64)
    private String abbOrderId;

    @Column(name = "reference", length = 128)
    private String reference;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "signature_valid")
    private Boolean signatureValid;

    @Column(name = "processed", nullable = false)
    private Boolean processed = false;

    @Column(name = "processing_result", length = 512)
    private String processingResult;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
