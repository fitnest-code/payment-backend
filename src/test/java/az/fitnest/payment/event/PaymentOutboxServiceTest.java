package az.fitnest.payment.event;

import az.fitnest.payment.model.entity.PaymentOutboxEvent;
import az.fitnest.payment.repository.PaymentOutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers {@code PaymentOutboxService.requestSubscriptionAssignment(...)}: blank-orderId skip,
 * idempotency for repeated callbacks, and the exact payload shape handed to the relay.
 *
 * <p>The payload key set is a contract: the backend implementation guide allows ONLY these
 * keys to cross the outbox boundary — no campaign keys (campaignId/bonusMonths/campaignApplied).</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentOutboxServiceTest {

    private static final String ORDER_ID = "ORD-2000";

    @Mock
    private PaymentOutboxEventRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PaymentOutboxService service;

    @BeforeEach
    void setUp() {
        service = new PaymentOutboxService(outboxRepository, objectMapper);
    }

    @Test
    @DisplayName("Blank or null orderId -> nothing is enqueued, repository untouched")
    void testRequestSubscriptionAssignment_BlankOrderIdIsSkipped() {
        service.requestSubscriptionAssignment(7L, 100L, 200L, true, "");
        service.requestSubscriptionAssignment(7L, 100L, 200L, true, "   ");
        service.requestSubscriptionAssignment(7L, 100L, 200L, true, null);

        verifyNoInteractions(outboxRepository);
    }

    @Test
    @DisplayName("Already enqueued for this orderId -> idempotent, no second row")
    void testRequestSubscriptionAssignment_AlreadyEnqueuedIsIdempotent() {
        when(outboxRepository.existsByAggregateIdAndEventType(
                ORDER_ID, OutboxEventType.SUBSCRIPTION_ASSIGNMENT_REQUESTED)).thenReturn(true);

        service.requestSubscriptionAssignment(7L, 100L, 200L, true, ORDER_ID);

        verify(outboxRepository, times(1))
                .existsByAggregateIdAndEventType(ORDER_ID, OutboxEventType.SUBSCRIPTION_ASSIGNMENT_REQUESTED);
        verify(outboxRepository, never()).save(any(PaymentOutboxEvent.class));
    }

    @Test
    @DisplayName("New orderId -> pending row enqueued with exactly the four contract fields + orderId")
    void testRequestSubscriptionAssignment_NewOrderEnqueuesExpectedPayload() throws Exception {
        when(outboxRepository.existsByAggregateIdAndEventType(
                ORDER_ID, OutboxEventType.SUBSCRIPTION_ASSIGNMENT_REQUESTED)).thenReturn(false);

        service.requestSubscriptionAssignment(7L, 100L, 200L, true, ORDER_ID);

        ArgumentCaptor<PaymentOutboxEvent> captor = ArgumentCaptor.forClass(PaymentOutboxEvent.class);
        verify(outboxRepository, times(1)).save(captor.capture());
        PaymentOutboxEvent saved = captor.getValue();

        assertEquals(ORDER_ID, saved.getAggregateId());
        assertEquals(OutboxEventType.SUBSCRIPTION_ASSIGNMENT_REQUESTED, saved.getEventType());
        assertNull(saved.getTopic(), "assignment is delivered over gRPC, not Kafka");
        assertEquals(PaymentOutboxEvent.STATUS_PENDING, saved.getStatus());
        assertEquals(0, saved.getAttempts());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getNextAttemptAt());

        JsonNode payload = objectMapper.readTree(saved.getPayload());
        Set<String> keys = new HashSet<>();
        payload.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("userId", "packageId", "optionId", "autoPaymentEnabled", "orderId"), keys);
        assertEquals(7L, payload.get("userId").asLong());
        assertEquals(100L, payload.get("packageId").asLong());
        assertEquals(200L, payload.get("optionId").asLong());
        assertTrue(payload.get("autoPaymentEnabled").asBoolean());
        assertEquals(ORDER_ID, payload.get("orderId").asText());

        // Contract lock: no campaign keys ever leave payment-backend through the outbox.
        assertFalse(keys.stream().map(String::toLowerCase)
                .anyMatch(k -> k.contains("campaign") || k.contains("bonus")));
    }

    @Test
    @DisplayName("Null autoPaymentEnabled is preserved as JSON null (relay defaults it to false)")
    void testRequestSubscriptionAssignment_NullAutoPaymentPreserved() throws Exception {
        when(outboxRepository.existsByAggregateIdAndEventType(
                ORDER_ID, OutboxEventType.SUBSCRIPTION_ASSIGNMENT_REQUESTED)).thenReturn(false);

        service.requestSubscriptionAssignment(7L, 100L, 200L, null, ORDER_ID);

        ArgumentCaptor<PaymentOutboxEvent> captor = ArgumentCaptor.forClass(PaymentOutboxEvent.class);
        verify(outboxRepository, times(1)).save(captor.capture());
        JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
        assertTrue(payload.get("autoPaymentEnabled").isNull());
    }
}
