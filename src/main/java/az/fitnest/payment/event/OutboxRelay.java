package az.fitnest.payment.event;

import az.fitnest.payment.client.UserSubscriptionGrpcClient;
import az.fitnest.payment.model.entity.PaymentOutboxEvent;
import az.fitnest.payment.repository.PaymentOutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains {@link PaymentOutboxEvent} rows and delivers them to their destination.
 *
 * <p>Delivery is at-least-once: a row is only marked published after the broker acknowledges
 * the record (or order-backend acknowledges the gRPC call), and a crash mid-delivery simply
 * leaves the row pending for the next pass. Consumers must therefore be idempotent.</p>
 */
@Slf4j
@Component
public class OutboxRelay {

    private static final Duration BASE_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);
    private static final long SEND_TIMEOUT_SECONDS = 15;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final PaymentOutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final UserSubscriptionGrpcClient userSubscriptionGrpcClient;
    private final ObjectMapper objectMapper;

    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Counter deadCounter;

    @Value("${app.outbox.enabled:true}")
    private boolean enabled;

    @Value("${app.outbox.batch-size:100}")
    private int batchSize;

    @Value("${app.outbox.max-attempts:10}")
    private int maxAttempts;

    @Value("${app.outbox.retention-hours:168}")
    private long retentionHours;

    public OutboxRelay(PaymentOutboxEventRepository outboxRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       UserSubscriptionGrpcClient userSubscriptionGrpcClient,
                       ObjectMapper objectMapper,
                       MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.userSubscriptionGrpcClient = userSubscriptionGrpcClient;
        this.objectMapper = objectMapper;
        this.publishedCounter = Counter.builder("payment.outbox.published").register(meterRegistry);
        this.failedCounter = Counter.builder("payment.outbox.failed").register(meterRegistry);
        this.deadCounter = Counter.builder("payment.outbox.dead").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")
    @Transactional
    public void relayPendingEvents() {
        if (!enabled) {
            return;
        }

        List<PaymentOutboxEvent> batch = outboxRepository.lockPendingBatch(Instant.now(), batchSize);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("[Outbox] Dispatching {} event(s)", batch.size());
        for (PaymentOutboxEvent event : batch) {
            try {
                dispatch(event);
                markPublished(event);
            } catch (Exception e) {
                markFailed(event, e);
            }
        }
    }

    private void dispatch(PaymentOutboxEvent event) throws Exception {
        if (OutboxEventType.SUBSCRIPTION_ASSIGNMENT_REQUESTED.equals(event.getEventType())) {
            assignSubscription(event);
            return;
        }

        if (event.getTopic() == null || event.getTopic().isBlank()) {
            throw new IllegalStateException("Outbox event " + event.getId() + " has no destination topic");
        }

        // Keyed by orderId so all events for one order land on the same partition and stay ordered.
        kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void assignSubscription(PaymentOutboxEvent event) throws Exception {
        JsonNode payload = objectMapper.readTree(event.getPayload());
        Long userId = payload.path("userId").isNull() ? null : payload.path("userId").asLong();
        Long packageId = payload.path("packageId").isNull() ? null : payload.path("packageId").asLong();
        Long optionId = payload.path("optionId").isNull() ? null : payload.path("optionId").asLong();
        boolean autoPayment = payload.path("autoPaymentEnabled").asBoolean(false);

        if (userId == null || packageId == null || optionId == null) {
            // Unrecoverable: retrying cannot supply the missing identifiers.
            throw new IllegalStateException("Incomplete subscription assignment payload for orderId="
                    + event.getAggregateId());
        }

        userSubscriptionGrpcClient.assignSubscriptionToUser(userId, packageId, optionId, autoPayment);
        log.info("[Outbox] Subscription assigned for userId={}, orderId={}", userId, event.getAggregateId());
    }

    private void markPublished(PaymentOutboxEvent event) {
        event.setStatus(PaymentOutboxEvent.STATUS_PUBLISHED);
        event.setPublishedAt(Instant.now());
        event.setAttempts(event.getAttempts() + 1);
        event.setLastError(null);
        outboxRepository.save(event);
        publishedCounter.increment();
    }

    private void markFailed(PaymentOutboxEvent event, Exception e) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));

        if (attempts >= maxAttempts) {
            event.setStatus(PaymentOutboxEvent.STATUS_DEAD);
            deadCounter.increment();
            log.error("[Outbox] Event {} ({}) dead after {} attempts for aggregate {}. Needs manual replay.",
                    event.getId(), event.getEventType(), attempts, event.getAggregateId(), e);
        } else {
            event.setNextAttemptAt(Instant.now().plus(backoffFor(attempts)));
            failedCounter.increment();
            log.warn("[Outbox] Event {} ({}) attempt {} failed, retrying later: {}",
                    event.getId(), event.getEventType(), attempts, e.toString());
        }
        outboxRepository.save(event);
    }

    private static Duration backoffFor(int attempts) {
        Duration backoff = BASE_BACKOFF.multipliedBy(1L << Math.min(attempts - 1, 16));
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    /** Keeps the outbox from growing without bound; dead rows are retained for investigation. */
    @Scheduled(cron = "${app.outbox.cleanup-cron:0 30 * * * *}")
    @Transactional
    public void purgePublishedEvents() {
        if (!enabled) {
            return;
        }
        try {
            int deleted = outboxRepository.deletePublishedBefore(
                    Instant.now().minus(Duration.ofHours(retentionHours)));
            if (deleted > 0) {
                log.info("[Outbox] Purged {} delivered event(s)", deleted);
            }
        } catch (Exception e) {
            log.error("[Outbox] Purge failed", e);
        }
    }
}
