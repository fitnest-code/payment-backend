package az.fitnest.payment.event;

import az.fitnest.payment.client.UserSubscriptionGrpcClient;
import az.fitnest.payment.model.entity.PaymentOutboxEvent;
import az.fitnest.payment.repository.PaymentOutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the SUBSCRIPTION_ASSIGNMENT_REQUESTED delivery path of the outbox relay
 * ({@link OutboxRelay#relayPendingEvents()}), i.e. the private {@code assignSubscription}
 * handler around L111-126.
 *
 * <p>The contract locked here: the relay forwards ONLY userId/packageId/optionId/
 * autoPaymentEnabled to order-backend. Campaign keys (campaignId, bonusMonths,
 * campaignApplied) must never cross the outbox boundary — the backend implementation
 * guide derives the whole October-campaign state on its side.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String ORDER_ID = "ORD-1000";

    @Mock
    private PaymentOutboxEventRepository outboxRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private UserSubscriptionGrpcClient userSubscriptionGrpcClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outboxRepository, kafkaTemplate, userSubscriptionGrpcClient,
                objectMapper, new SimpleMeterRegistry());
        // @Value-backed defaults are 0/false on a plain instantiation; enable the relay
        // the way application.yml would.
        ReflectionTestUtils.setField(relay, "enabled", true);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxAttempts", 10);
        ReflectionTestUtils.setField(relay, "retentionHours", 168L);
    }

    private PaymentOutboxEvent assignmentEvent(String payload) {
        return PaymentOutboxEvent.builder()
                .id(1L)
                .aggregateId(ORDER_ID)
                .eventType(OutboxEventType.SUBSCRIPTION_ASSIGNMENT_REQUESTED)
                .topic(null)
                .payload(payload)
                .status(PaymentOutboxEvent.STATUS_PENDING)
                .attempts(0)
                .nextAttemptAt(Instant.now())
                .createdAt(Instant.now())
                .build();
    }

    private void givenDueBatch(PaymentOutboxEvent event) {
        when(outboxRepository.lockPendingBatch(any(Instant.class), eq(100))).thenReturn(List.of(event));
    }

    @Test
    @DisplayName("Happy path: forwards ONLY the four contract fields; campaign keys in the payload are ignored")
    void testAssignSubscription_HappyPathForwardsOnlyContractFields() {
        String payload = """
                {
                  "userId": 7,
                  "packageId": 100,
                  "optionId": 200,
                  "autoPaymentEnabled": true,
                  "orderId": "%s",
                  "campaignId": 42,
                  "bonusMonths": 3,
                  "campaignApplied": true
                }
                """.formatted(ORDER_ID);
        PaymentOutboxEvent event = assignmentEvent(payload);
        givenDueBatch(event);

        relay.relayPendingEvents();

        verify(userSubscriptionGrpcClient, times(1)).assignSubscriptionToUser(7L, 100L, 200L, true);
        verifyNoMoreInteractions(userSubscriptionGrpcClient);
        verifyNoInteractions(kafkaTemplate);
        assertEquals(PaymentOutboxEvent.STATUS_PUBLISHED, event.getStatus());
        assertEquals(1, event.getAttempts());
        assertNotNull(event.getPublishedAt());
        assertNull(event.getLastError());
        verify(outboxRepository, times(1)).save(event);
    }

    @Test
    @DisplayName("ABSENT userId key is NOT rejected - 0L is forwarded (known gap: guard only sees explicit nulls)")
    void testAssignSubscription_AbsentUserIdKeyForwardsZero() {
        PaymentOutboxEvent event = assignmentEvent("""
                {"packageId": 100, "optionId": 200, "autoPaymentEnabled": true}""");
        givenDueBatch(event);

        relay.relayPendingEvents();

        // Production behaviour (OutboxRelay.assignSubscription, L113): path() returns MissingNode
        // for an absent key, isNull() is false and asLong() yields 0, so the incomplete-payload
        // guard does NOT trip. Explicit JSON nulls are covered by the tests below. Locked here
        // so closing this gap becomes a deliberate, test-visible change.
        verify(userSubscriptionGrpcClient, times(1)).assignSubscriptionToUser(0L, 100L, 200L, true);
        verifyNoMoreInteractions(userSubscriptionGrpcClient);
        verifyNoInteractions(kafkaTemplate);
        assertEquals(PaymentOutboxEvent.STATUS_PUBLISHED, event.getStatus());
        assertNull(event.getLastError());
        verify(outboxRepository, times(1)).save(event);
    }

    @Test
    @DisplayName("Explicit null packageId -> IllegalStateException, no gRPC call, row retried")
    void testAssignSubscription_NullPackageIdFailsUnrecoverably() {
        assertIncompletePayloadFails("""
                {"userId": 7, "packageId": null, "optionId": 200, "autoPaymentEnabled": true}""");
    }

    @Test
    @DisplayName("Explicit null optionId -> IllegalStateException, no gRPC call, row retried")
    void testAssignSubscription_NullOptionIdFailsUnrecoverably() {
        assertIncompletePayloadFails("""
                {"userId": 7, "packageId": 100, "optionId": null, "autoPaymentEnabled": true}""");
    }

    @Test
    @DisplayName("Explicit null userId is treated as missing -> IllegalStateException")
    void testAssignSubscription_NullUserIdFailsUnrecoverably() {
        assertIncompletePayloadFails("""
                {"userId": null, "packageId": 100, "optionId": 200, "autoPaymentEnabled": true}""");
    }

    private void assertIncompletePayloadFails(String payload) {
        PaymentOutboxEvent event = assignmentEvent(payload);
        givenDueBatch(event);

        relay.relayPendingEvents();

        verifyNoInteractions(userSubscriptionGrpcClient);
        verifyNoInteractions(kafkaTemplate);
        // The IllegalStateException is recorded as the delivery error and the row is retried
        // with backoff instead of being marked dead (attempts 1 < maxAttempts 10).
        assertEquals(PaymentOutboxEvent.STATUS_PENDING, event.getStatus());
        assertEquals(1, event.getAttempts());
        assertNotNull(event.getLastError());
        assertTrue(event.getLastError().startsWith("IllegalStateException"),
                "expected IllegalStateException, got: " + event.getLastError());
        assertTrue(event.getLastError().contains(
                        "Incomplete subscription assignment payload for orderId=" + ORDER_ID),
                "unexpected error: " + event.getLastError());
        assertTrue(event.getNextAttemptAt().isAfter(Instant.now()), "backoff must reschedule the row");
        verify(outboxRepository, times(1)).save(event);
    }

    @Test
    @DisplayName("assignSubscription itself throws IllegalStateException for an incomplete payload")
    void testAssignSubscription_DirectCallThrowsIllegalState() throws Exception {
        Method method = OutboxRelay.class.getDeclaredMethod("assignSubscription", PaymentOutboxEvent.class);
        method.setAccessible(true);
        PaymentOutboxEvent event = assignmentEvent("""
                {"userId": 7, "packageId": null, "optionId": null, "autoPaymentEnabled": true}""");

        Object thrown = invoke(method, event);
        InvocationTargetException wrapper = assertInstanceOf(InvocationTargetException.class, thrown);
        assertInstanceOf(IllegalStateException.class, wrapper.getCause());
        assertTrue(wrapper.getCause().getMessage().contains(
                "Incomplete subscription assignment payload for orderId=" + ORDER_ID));
        verifyNoInteractions(userSubscriptionGrpcClient);
    }

    private Object invoke(Method method, PaymentOutboxEvent event) {
        try {
            return method.invoke(relay, event);
        } catch (IllegalAccessException | InvocationTargetException e) {
            // invoke() wraps the handler's own exception; only propagate true failures.
            if (e instanceof InvocationTargetException ite) {
                return ite;
            }
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("Missing autoPaymentEnabled does NOT fail; defaults to false and is forwarded")
    void testAssignSubscription_MissingAutoPaymentEnabledDefaultsToFalse() {
        PaymentOutboxEvent event = assignmentEvent("""
                {"userId": 7, "packageId": 100, "optionId": 200}""");
        givenDueBatch(event);

        relay.relayPendingEvents();

        verify(userSubscriptionGrpcClient, times(1)).assignSubscriptionToUser(7L, 100L, 200L, false);
        verifyNoMoreInteractions(userSubscriptionGrpcClient);
        assertEquals(PaymentOutboxEvent.STATUS_PUBLISHED, event.getStatus());
        assertNull(event.getLastError());
    }

    @Test
    @DisplayName("Assignment events are never routed to Kafka")
    void testAssignSubscription_NeverSentToKafka() {
        PaymentOutboxEvent event = assignmentEvent("""
                {"userId": 7, "packageId": 100, "optionId": 200, "autoPaymentEnabled": false}""");
        givenDueBatch(event);

        relay.relayPendingEvents();

        verifyNoInteractions(kafkaTemplate);
        verify(outboxRepository, times(1)).lockPendingBatch(any(Instant.class), eq(100));
    }
}
