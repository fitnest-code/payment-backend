package az.fitnest.payment.service;

import az.fitnest.payment.client.UserGrpcClient;
import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.exception.ResourceNotFoundException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPaymentHistoryLookupTest {

    @Mock
    private UserCardRepository userCardRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private EpointIntegrationService integrationService;
    @Mock
    private AbbIntegrationService abbIntegrationService;
    @Mock
    private UserDisplayNameResolver userDisplayNameResolver;
    @Mock
    private MessageSource messageSource;
    @Mock
    private UserGrpcClient userGrpcClient;

    @InjectMocks
    private UserPaymentService userPaymentService;

    private Payment widgetStoredAsTe;

    @BeforeEach
    void setUp() {
        widgetStoredAsTe = payment(
                111L, 27L, "WIDGET_PAYMENT", "SUCCESS", "te022241588",
                "3e633345-d5f7-4aa6-9e78-4915b6e4236a", "625020306717");
        lenient().when(userDisplayNameResolver.resolveFullName(27L)).thenReturn("Tester");
        lenient().when(paymentRepository.findByOrderId(anyString())).thenReturn(Optional.empty());
        lenient().when(paymentRepository.findByTransactionId(anyString())).thenReturn(Optional.empty());
        lenient().when(paymentRepository.findByTransactionIdIn(anyCollection())).thenReturn(List.of());
    }

    @Test
    void historyLookupFindsWidgetPaymentWhenClientSendsTwAndDbHasTe() {
        stubStored(widgetStoredAsTe);

        PaymentResponse response = userPaymentService.getPaymentByTransactionId("tw022241588", 27L);

        assertEquals(111L, response.paymentId());
        assertEquals("te022241588", response.transactionId());
        assertEquals("625020306717", response.rrn());
        assertEquals("************0239", response.maskedPan());
        assertEquals("Uğurlu", response.status());
    }

    @Test
    void adminHistoryLookupUsesSameTwTeCandidates() {
        stubStored(widgetStoredAsTe);

        PaymentResponse response = userPaymentService.getPaymentByTransactionIdAdmin("tw022241588");

        assertEquals(111L, response.paymentId());
        assertEquals("625020306717", response.rrn());
    }

    @Test
    void historyLookupDoesNotReturnCardPaymentWithSameDigitsForTwQuery() {
        Payment cardOnly = payment(
                201L, 27L, "PAYMENT", "SUCCESS", "te022241588",
                "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee", "999");
        stubStored(cardOnly);

        assertThrows(ResourceNotFoundException.class,
                () -> userPaymentService.getPaymentByTransactionId("tw022241588", 27L));
    }

    @Test
    void historyLookupExactTwWinsWhenWidgetAndCardShareDigits() {
        Payment widgetTw = payment(
                112L, 27L, "WIDGET_PAYMENT", "SUCCESS", "tw022241588",
                "3e633345-d5f7-4aa6-9e78-4915b6e4236a", "625020306717");
        when(paymentRepository.findByTransactionId("tw022241588")).thenReturn(Optional.of(widgetTw));

        PaymentResponse response = userPaymentService.getPaymentByTransactionId("tw022241588", 27L);

        assertEquals(112L, response.paymentId());
        assertEquals("tw022241588", response.transactionId());
    }

    @Test
    void historyLookupExactTeWinsForCardWhenWidgetAlsoExists() {
        Payment cardTe = payment(
                201L, 27L, "PAYMENT", "SUCCESS", "te022241588",
                "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee", "999");
        when(paymentRepository.findByTransactionId("te022241588")).thenReturn(Optional.of(cardTe));

        PaymentResponse response = userPaymentService.getPaymentByTransactionId("te022241588", 27L);

        assertEquals(201L, response.paymentId());
        assertEquals("999", response.rrn());
    }

    @Test
    void historyLookupDoesNotReturnAnotherUsersWidgetAlias() {
        Payment otherUser = payment(
                300L, 99L, "WIDGET_PAYMENT", "SUCCESS", "te022241588",
                "cccccccc-bbbb-cccc-dddd-eeeeeeeeeeee", "625020306717");
        stubStored(otherUser);

        assertThrows(ResourceNotFoundException.class,
                () -> userPaymentService.getPaymentByTransactionId("tw022241588", 27L));
    }

    @Test
    void historyLookupStill404WhenNoCandidateExists() {
        assertThrows(ResourceNotFoundException.class,
                () -> userPaymentService.getPaymentByTransactionId("tw022249999", 27L));
    }

    private void stubStored(Payment... payments) {
        stubInLookup(payments);
    }

    private void stubInLookup(Payment... payments) {
        when(paymentRepository.findByTransactionIdIn(anyCollection())).thenAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(0);
            List<Payment> out = new ArrayList<>();
            for (Payment payment : payments) {
                if (ids.contains(payment.getTransactionId())) {
                    out.add(payment);
                }
            }
            return out;
        });
    }

    private static Payment payment(
            Long id, Long userId, String type, String status, String transactionId, String orderId, String rrn) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setUserId(userId);
        payment.setProvider("EPOINT");
        payment.setType(type);
        payment.setStatus(status);
        payment.setAmount(0.10);
        payment.setCurrency("AZN");
        payment.setTransactionId(transactionId);
        payment.setOrderId(orderId);
        payment.setRrn(rrn);
        payment.setCardMask("************0239");
        payment.setCreatedDate(LocalDateTime.of(2026, 9, 7, 20, 21, 21));
        return payment;
    }
}
