package az.fitnest.payment.service;

import az.fitnest.payment.dto.common.PaymentOrderStatusV2Response;
import az.fitnest.payment.exception.ForbiddenException;
import az.fitnest.payment.exception.ResourceNotFoundException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class PaymentOrderStatusV2Test {

    @Mock private UserCardRepository userCardRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private EpointIntegrationService integrationService;
    @Mock private AbbIntegrationService abbIntegrationService;
    @Mock private UserDisplayNameResolver userDisplayNameResolver;

    @InjectMocks
    private UserPaymentService userPaymentService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void getPaymentOrderStatusByOrderId_returnsStatusAndTransactionId() {
        Payment payment = payment(42L, "SUCCESS", "te022241588", "ord_abc");
        when(paymentRepository.findByOrderId("ord_abc")).thenReturn(Optional.of(payment));

        PaymentOrderStatusV2Response response =
                userPaymentService.getPaymentOrderStatusByOrderId("ord_abc", 42L);

        assertEquals("SUCCESS", response.status());
        assertEquals("te022241588", response.transactionId());
    }

    @Test
    void getRawPaymentStatusByOrderId_delegatesToV2Shape() {
        Payment payment = payment(7L, "PENDING_3DS", "tw022240001", "ord_x");
        when(paymentRepository.findByOrderId("ord_x")).thenReturn(Optional.of(payment));

        assertEquals("PENDING", userPaymentService.getRawPaymentStatusByOrderId("ord_x", 7L));
    }

    @Test
    void getPaymentOrderStatusByOrderId_rejectsOtherUsersPayment() {
        Payment payment = payment(99L, "SUCCESS", "te1", "ord_y");
        when(paymentRepository.findByOrderId("ord_y")).thenReturn(Optional.of(payment));

        assertThrows(ForbiddenException.class,
                () -> userPaymentService.getPaymentOrderStatusByOrderId("ord_y", 1L));
    }

    @Test
    void getPaymentOrderStatusByOrderId_notFound() {
        when(paymentRepository.findByOrderId("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> userPaymentService.getPaymentOrderStatusByOrderId("missing", 1L));
    }

    @ParameterizedTest
    @CsvSource({
            "SUCCESS, SUCCESS",
            "FAILED, FAILED",
            "ERROR, FAILED",
            "SERVER_ERROR, FAILED",
            "PENDING_USER_ACTION, PENDING",
            "NEW, PENDING",
            "PENDING, PENDING",
            "PENDING_3DS, PENDING",
            "REVERSED, CANCELLED",
            "REFUNDED, CANCELLED",
            "RETURNED, CANCELLED",
            "CANCELLED, CANCELLED",
            "WEIRD, PENDING"
    })
    void normalizeClientStatus_mapsKnownValues(String raw, String expected) {
        assertEquals(expected, UserPaymentService.normalizeClientStatus(raw));
    }

    @Test
    void normalizeClientStatus_nullIsPending() {
        assertEquals("PENDING", UserPaymentService.normalizeClientStatus(null));
    }

    private static Payment payment(Long userId, String status, String transactionId, String orderId) {
        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setStatus(status);
        payment.setTransactionId(transactionId);
        payment.setOrderId(orderId);
        // Non-pending so syncPendingProviderStatus is a no-op (no provider calls in unit test).
        return payment;
    }
}
