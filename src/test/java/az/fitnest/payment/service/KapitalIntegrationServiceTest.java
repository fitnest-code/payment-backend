package az.fitnest.payment.service;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.kapital.*;
import az.fitnest.payment.dto.kapital.KapitalInitiateRequest;
import az.fitnest.payment.dto.kapital.KapitalInitiateResponse;
import az.fitnest.payment.dto.kapital.KapitalRefundRequest;
import az.fitnest.payment.dto.kapital.KapitalRefundResponse;
import az.fitnest.payment.exception.KapitalPaymentException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.service.coin.CoinCheckoutHelper;
import az.fitnest.payment.service.kapital.KapitalPaymentStore;
import az.fitnest.payment.service.kapital.KapitalStatusMapper;
import az.fitnest.payment.util.PaymentSecretEncryption;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KapitalIntegrationServiceTest {

    private static final String SUCCESS_URL = "https://fitnest.az/ok";
    private static final String ERROR_URL = "https://fitnest.az/err";

    @Mock private KapitalRestClient restClient;
    @Mock private KapitalPaymentStore paymentStore;
    @Mock private PaymentSubscriptionService paymentSubscriptionService;
    @Mock private SubscriptionPackageGrpcClient packageClient;
    @Mock private CoinCheckoutHelper coinCheckoutHelper;

    private final ObjectMapper mapper = new ObjectMapper();
    private KapitalProperties properties;
    private KapitalIntegrationService service;

    @BeforeEach
    void setUp() {
        properties = new KapitalProperties();
        properties.setCallbackUrl("https://api.fitnest.az/payment/kapital/result");
        properties.setSuccessRedirectUrl(SUCCESS_URL);
        properties.setErrorRedirectUrl(ERROR_URL);
        service = new KapitalIntegrationService(properties, restClient, paymentStore, new KapitalStatusMapper(),
                paymentSubscriptionService, packageClient, coinCheckoutHelper,
                new PaymentSecretEncryption("unit-test-key"));
    }

    private KapitalOrderResponse order(String status, String pmo) throws Exception {
        String json = "{\"order\":{\"id\":101,\"status\":\"" + status + "\""
                + (pmo != null ? ",\"tranList\":[{\"pmoResultCode\":\"" + pmo + "\"}]" : "") + "}}";
        return mapper.readValue(json, KapitalOrderResponse.class);
    }

    private Payment pending() {
        Payment p = new Payment();
        p.setProvider("KAPITAL");
        p.setProviderOrderId("101");
        p.setOrderId("ORD-1");
        p.setTransactionId("ORD-1");
        p.setStatus("PENDING");
        p.setCallbackProcessed(false);
        p.setAmount(10.0);
        return p;
    }

    // ---------------------------------------------------------------- result

    @Test
    void fullyPaidFinalizesOnceAndAssignsSubscription() throws Exception {
        Payment payment = pending();
        when(paymentStore.findByProviderOrderIdForUpdate("101")).thenReturn(Optional.of(payment));
        when(restClient.getOrder("101")).thenReturn(order("FullyPaid", null));
        // Real store mutates the payment; emulate the part the idempotency check depends on.
        when(paymentStore.markSuccess(eq(payment), any())).thenAnswer(inv -> {
            payment.setStatus("SUCCESS");
            payment.setCallbackProcessed(true);
            return payment;
        });

        assertEquals(SUCCESS_URL, service.processResult("101"));
        // Second callback for the same payment must not touch the bank or assign again.
        assertEquals(SUCCESS_URL, service.processResult("101"));

        verify(restClient, times(1)).getOrder("101");
        verify(paymentSubscriptionService, times(1)).assignFromPaymentDescription(payment);
    }

    @Test
    void declinedMarksFailedWithPmoResultCode() throws Exception {
        Payment payment = pending();
        when(paymentStore.findByProviderOrderIdForUpdate("101")).thenReturn(Optional.of(payment));
        when(restClient.getOrder("101")).thenReturn(order("Declined", "51"));
        when(paymentStore.markFailed(eq(payment), anyString(), eq("51"), anyString())).thenAnswer(inv -> {
            payment.setStatus("FAILED");
            return payment;
        });

        assertEquals(ERROR_URL, service.processResult("101"));

        verify(paymentStore).markFailed(eq(payment), contains("Declined"), eq("51"), anyString());
        verifyNoInteractions(paymentSubscriptionService);
    }

    @Test
    void partiallyPaidGoesToManualReviewWithoutSubscription() throws Exception {
        Payment payment = pending();
        when(paymentStore.findByProviderOrderIdForUpdate("101")).thenReturn(Optional.of(payment));
        KapitalOrderResponse partial = order("PartiallyPaid", null);
        when(restClient.getOrder("101")).thenReturn(partial);
        when(paymentStore.markManualReview(eq(payment), anyString(), eq(partial), anyString())).thenAnswer(inv -> {
            payment.setStatus("MANUAL_REVIEW");
            return payment;
        });

        assertEquals(ERROR_URL, service.processResult("101"));

        verify(paymentStore).markManualReview(eq(payment), anyString(), eq(partial), anyString());
        verifyNoInteractions(paymentSubscriptionService);
    }

    @Test
    void preparingStaysPendingOnCallbackButFailsAfterTimeoutOnReconcile() throws Exception {
        Payment payment = pending();
        payment.setCreatedDate(LocalDateTime.now().minusHours(3));
        properties.setPreparingTimeoutMs(3_600_000L);
        when(paymentStore.findByProviderOrderIdForUpdate("101")).thenReturn(Optional.of(payment));
        when(restClient.getOrder("101")).thenReturn(order("Preparing", null));

        // Payer-facing callback never abandons: they may still be paying.
        assertEquals(SUCCESS_URL, service.processResult("101"));
        verify(paymentStore, never()).markFailed(any(), any(), any(), any());

        // Reconciler does.
        service.reconcileOne("101");
        verify(paymentStore).markFailed(eq(payment), anyString(), eq("TIMEOUT"), anyString());
    }

    @Test
    void bankOutageLeavesPaymentPending() {
        Payment payment = pending();
        when(paymentStore.findByProviderOrderIdForUpdate("101")).thenReturn(Optional.of(payment));
        when(restClient.getOrder("101")).thenThrow(new KapitalPaymentException("KAPITAL_UNAVAILABLE", "down"));

        assertEquals(SUCCESS_URL, service.processResult("101"));
        verify(paymentStore, never()).markFailed(any(), any(), any(), any());
        verify(paymentStore, never()).markSuccess(any(), any());
    }

    @Test
    void unknownOrderRedirectsToError() {
        when(paymentStore.findByProviderOrderIdForUpdate("999")).thenReturn(Optional.empty());
        assertEquals(ERROR_URL, service.processResult("999"));
    }

    // ---------------------------------------------------------------- init

    @Test
    void initRejectsUnsupportedTaksitBeforeAnyIo() {
        KapitalInitiateRequest request = KapitalInitiateRequest.builder()
                .packageId(1L).optionId(2L).installmentMonths(9).build();

        KapitalPaymentException ex = assertThrows(KapitalPaymentException.class,
                () -> service.initiatePayment(1L, request));

        assertEquals("INVALID_INSTALLMENT", ex.getErrorCode());
        verifyNoInteractions(restClient, paymentStore, packageClient);
    }

    @Test
    void taksitCannotExceedPackageDuration() {
        when(packageClient.getOptionPriceCurrency(eq(1L), eq(2L), eq(1L)))
                .thenReturn(new SubscriptionPackageGrpcClient.OptionPriceCurrency(30.0, "AZN", 3));
        KapitalInitiateRequest request = KapitalInitiateRequest.builder()
                .packageId(1L).optionId(2L).installmentMonths(6).build();

        assertThrows(KapitalPaymentException.class, () -> service.initiatePayment(1L, request));
        verifyNoInteractions(restClient, paymentStore);
    }

    @Test
    void initWithTaksitSendsDescriptionAndReturnsHppUrl() throws Exception {
        when(packageClient.getOptionPriceCurrency(eq(1L), eq(2L), eq(1L)))
                .thenReturn(new SubscriptionPackageGrpcClient.OptionPriceCurrency(30.0, "AZN", 12));
        when(coinCheckoutHelper.applyForSubscription(any(), any(), any(), any(), anyDouble()))
                .thenAnswer(inv -> CoinCheckoutHelper.AppliedCheckout.noCoins(inv.getArgument(4, Double.class)));
        Payment payment = pending();
        when(paymentStore.createPending(eq(1L), anyString(), eq(30.0), eq("AZN"), anyString(), eq(6)))
                .thenReturn(payment);
        KapitalCreateOrderResponse bankResponse = mapper.readValue(
                "{\"order\":{\"id\":\"101\",\"password\":\"pw\",\"hppUrl\":\"https://txpgtst.kapitalbank.az/flex\",\"status\":\"Preparing\"}}",
                KapitalCreateOrderResponse.class);
        when(restClient.createOrder(any())).thenReturn(bankResponse);

        KapitalInitiateResponse response = service.initiatePayment(1L, KapitalInitiateRequest.builder()
                .packageId(1L).optionId(2L).installmentMonths(6).build());

        var captor = org.mockito.ArgumentCaptor.forClass(KapitalCreateOrderRequest.class);
        verify(restClient).createOrder(captor.capture());
        assertEquals("TAKSIT=6", captor.getValue().getOrder().getDescription());
        assertEquals("30.00", captor.getValue().getOrder().getAmount());
        assertEquals("Order_SMS", captor.getValue().getOrder().getTypeRid());
        assertEquals("https://txpgtst.kapitalbank.az/flex?id=101&password=pw", response.getHppUrl());
        // Password is persisted encrypted, never in the stored redirect URL.
        verify(paymentStore).markRegistered(eq(payment), eq("101"),
                argThat(enc -> enc != null && !enc.contains("pw")),
                eq("https://txpgtst.kapitalbank.az/flex?id=101"));
    }

    @Test
    void hppUrlNormalization() {
        assertEquals("https://h/flex?id=1&password=a%26b",
                KapitalIntegrationService.buildHppUrl("https://h/flex", "1", "a&b"));
        assertEquals("https://h/flex?id=1&password=p",
                KapitalIntegrationService.buildHppUrl("https://h/", "1", "p"));
        assertEquals("https://h/flex?x=y&id=1&password=p",
                KapitalIntegrationService.buildHppUrl("https://h/flex?x=y", "1", "p"));
    }

    @Test
    void cardInstallmentNormalization() {
        assertNull(service.normalizeInstallmentMonths(null));
        assertNull(service.normalizeInstallmentMonths(0));
        assertEquals(12, service.normalizeInstallmentMonths(12));
        assertThrows(KapitalPaymentException.class, () -> service.normalizeInstallmentMonths(2));
    }

    @Test
    void eligibleInstallmentsFilteredByPackageDuration() {
        assertEquals(java.util.List.of(3, 6, 12), service.getEligibleInstallments(12));
        assertEquals(java.util.List.of(3, 6), service.getEligibleInstallments(6));
        assertEquals(java.util.List.of(3), service.getEligibleInstallments(3));
        assertEquals(java.util.List.of(), service.getEligibleInstallments(1));
        assertEquals(java.util.List.of(), service.getEligibleInstallments(0));
    }

    @Test
    void eligibleInstallmentsRespectConfiguredBankMonths() {
        properties.setActiveInstallmentMonths("3,6,12");
        assertEquals(java.util.List.of(3, 6), service.getEligibleInstallments(6));
        // When bank list expands later, only months ≤ package duration are returned.
        properties.setActiveInstallmentMonths("1,2,3,4,5,6,12");
        assertEquals(java.util.List.of(1, 2, 3, 4, 5, 6), service.getEligibleInstallments(6));
    }

    @Test
    void eligibleInstallmentsResolvedFromPackageOption() {
        when(packageClient.getOptionDurationMonths(eq(4L), eq(17L))).thenReturn(6);

        var response = service.getEligibleInstallmentsForOption(4L, 17L);

        assertEquals(java.util.List.of(3, 6), response.installmentMonths());
        verify(packageClient).getOptionDurationMonths(4L, 17L);
        verify(packageClient, never()).getOptionPriceCurrency(any(), any(), any());
        verify(packageClient, never()).getOptionPriceCurrency(any(), any());
    }

    // -------------------------------------------------------------- refund

    private static LocalDateTime bakuNowAsLocal(long minusDays) {
        return LocalDateTime.now(ZoneId.of("Asia/Baku")).minusDays(minusDays)
                .atZone(ZoneId.of("Asia/Baku")).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    @Test
    void sameDayFullIsVoidFullPartialIsVoidPartialLaterIsRefund() {
        Instant now = Instant.now();
        Payment today = pending();
        today.setCreatedDate(bakuNowAsLocal(0));
        // Guard against running exactly at Baku midnight.
        if (!KapitalIntegrationService.isSameBakuDay(today.getCreatedDate(), now)) {
            return;
        }

        var full = KapitalIntegrationService.planRefund(today, null, now);
        assertEquals("VOID_FULL", full.operation());
        assertEquals("Full", full.body().getTran().getVoidKind());
        assertNull(full.body().getTran().getType());
        assertTrue(full.full());

        var partial = KapitalIntegrationService.planRefund(today, 4.0, now);
        assertEquals("VOID_PARTIAL", partial.operation());
        assertEquals("Partial", partial.body().getTran().getVoidKind());
        assertEquals("4.00", partial.body().getTran().getAmount());
        assertFalse(partial.full());

        Payment old = pending();
        old.setCreatedDate(bakuNowAsLocal(2));
        var refund = KapitalIntegrationService.planRefund(old, null, now);
        assertEquals("REFUND", refund.operation());
        assertEquals("Refund", refund.body().getTran().getType());
        assertNull(refund.body().getTran().getVoidKind());
        assertEquals("10.00", refund.body().getTran().getAmount());
        assertTrue(refund.full());
    }

    @Test
    void refundRejectsAmountAbovePaid() {
        Payment p = pending();
        p.setCreatedDate(LocalDateTime.now());
        assertThrows(KapitalPaymentException.class,
                () -> KapitalIntegrationService.planRefund(p, 50.0, Instant.now()));
    }

    @Test
    void fullRefundMarksRefundedAndReverses() {
        Payment payment = pending();
        payment.setStatus("SUCCESS");
        payment.setCreatedDate(bakuNowAsLocal(3));
        when(paymentStore.findByProviderOrderId("101")).thenReturn(Optional.of(payment));

        KapitalRefundResponse response = service.refundPayment(
                KapitalRefundRequest.builder().providerOrderId("101").build());

        assertTrue(response.isSuccess());
        assertEquals("REFUND", response.getOperation());
        verify(restClient).execTran(eq("101"), any());
        verify(paymentStore).markRefunded(payment);
        verify(paymentSubscriptionService).onPaymentReversed(payment);
    }

    @Test
    void refundOfNonSuccessPaymentIsRejected() {
        Payment payment = pending();
        when(paymentStore.findByProviderOrderId("101")).thenReturn(Optional.of(payment));
        assertThrows(KapitalPaymentException.class, () -> service.refundPayment(
                KapitalRefundRequest.builder().providerOrderId("101").build()));
        verifyNoInteractions(restClient);
    }
}
