package az.fitnest.payment.event;

import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.PaymentOutboxEvent;
import az.fitnest.payment.repository.PaymentOutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes outbox rows in the caller's transaction ({@code REQUIRED}) so an event is committed
 * atomically with the payment change that caused it, or not at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentOutboxService {

    private final PaymentOutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordPaymentOutcome(Payment payment) {
        if (payment == null || payment.getOrderId() == null || payment.getOrderId().isBlank()) {
            return;
        }
        boolean success = "SUCCESS".equalsIgnoreCase(payment.getStatus());
        String eventType = success ? OutboxEventType.PAYMENT_SUCCEEDED : OutboxEventType.PAYMENT_FAILED;
        if (alreadyEnqueued(payment.getOrderId(), eventType)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("paymentId", payment.getId());
        payload.put("orderId", payment.getOrderId());
        payload.put("transactionId", payment.getTransactionId());
        payload.put("userId", payment.getUserId());
        payload.put("amount", payment.getAmount());
        payload.put("currency", payment.getCurrency());
        payload.put("provider", payment.getProvider());
        payload.put("status", payment.getStatus());
        payload.put("autoPaymentEnabled", payment.getAutoPaymentEnabled());
        payload.put("occurredAt", Instant.now().toString());

        enqueue(eventType, OutboxEventType.TOPIC_PAYMENT_EVENTS, payment.getOrderId(), payload);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordCardRegistered(Long userId, String cardId, String cardMask) {
        if (userId == null || cardId == null || cardId.isBlank()) {
            return;
        }
        String aggregateId = userId + ":" + cardId;
        if (alreadyEnqueued(aggregateId, OutboxEventType.CARD_REGISTERED)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", OutboxEventType.CARD_REGISTERED);
        payload.put("userId", userId);
        payload.put("cardId", cardId);
        // Only the masked form is ever published; the holder name is deliberately omitted.
        payload.put("cardMask", cardMask);
        payload.put("occurredAt", Instant.now().toString());

        enqueue(OutboxEventType.CARD_REGISTERED, OutboxEventType.TOPIC_CARD_EVENTS, aggregateId, payload);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void requestSubscriptionAssignment(Long userId, Long packageId, Long optionId,
                                              Boolean autoPaymentEnabled, String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        if (alreadyEnqueued(orderId, OutboxEventType.SUBSCRIPTION_ASSIGNMENT_REQUESTED)) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("packageId", packageId);
        payload.put("optionId", optionId);
        payload.put("autoPaymentEnabled", autoPaymentEnabled);
        payload.put("orderId", orderId);

        // No topic: the relay delivers this one over gRPC to order-backend.
        enqueue(OutboxEventType.SUBSCRIPTION_ASSIGNMENT_REQUESTED, null, orderId, payload);
    }

    private boolean alreadyEnqueued(String aggregateId, String eventType) {
        if (outboxRepository.existsByAggregateIdAndEventType(aggregateId, eventType)) {
            log.debug("[Outbox] Skip duplicate {} for aggregate {}", eventType, aggregateId);
            return true;
        }
        return false;
    }

    private void enqueue(String eventType, String topic, String aggregateId, Map<String, Object> payload) {
        try {
            PaymentOutboxEvent event = PaymentOutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .topic(topic)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(PaymentOutboxEvent.STATUS_PENDING)
                    .attempts(0)
                    .nextAttemptAt(Instant.now())
                    .createdAt(Instant.now())
                    .build();
            outboxRepository.save(event);
            log.debug("[Outbox] Enqueued {} for aggregate {}", eventType, aggregateId);
        } catch (JsonProcessingException e) {
            // Rolls back the surrounding transaction: committing a payment change whose event
            // could not be recorded would reintroduce exactly the drift the outbox prevents.
            throw new IllegalStateException("Failed to serialize outbox payload for " + eventType, e);
        }
    }
}
