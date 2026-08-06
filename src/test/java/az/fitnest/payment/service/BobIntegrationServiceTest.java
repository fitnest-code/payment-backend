package az.fitnest.payment.service;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.bob.BobProperties;
import az.fitnest.payment.client.bob.BobRestClient;
import az.fitnest.payment.dto.bob.*;
import az.fitnest.payment.exception.BobMaintenanceException;
import az.fitnest.payment.exception.BobPaymentException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.service.bob.BobCardService;
import az.fitnest.payment.service.bob.BobPaymentStore;
import az.fitnest.payment.service.bob.BobStatusMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BobIntegrationServiceTest {

    @Mock
    private BobProperties bobProperties;
    @Mock
    private BobRestClient bobRestClient;
    @Mock
    private BobPaymentStore paymentStore;
    @Mock
    private BobCardService bobCardService;
    @Mock
    private BobStatusMapper statusMapper;
    @Mock
    private PaymentSubscriptionService paymentSubscriptionService;
    @Mock
    private SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private BobIntegrationService bobIntegrationService;

    @BeforeEach
    void setUp() {
        lenient().when(bobProperties.isMaintenanceMode()).thenReturn(false);
        lenient().when(bobProperties.getDefaultCurrency()).thenReturn("AZN");
        lenient().when(bobProperties.getCallbackUrl()).thenReturn("https://api.fitnest.az/payment/bob/callback");
        lenient().when(bobProperties.getSuccessRedirectUrl()).thenReturn("https://fitnest.az/payment/success");
        lenient().when(bobProperties.getErrorRedirectUrl()).thenReturn("https://fitnest.az/payment/error");
    }

    @Test
    void testInitiatePayment_Success() {
        Long userId = 1L;
        BobInitiateRequest request = BobInitiateRequest.builder()
                .packageId(10L)
                .optionId(20L)
                .saveCard(true)
                .description("Test Payment")
                .build();

        when(subscriptionPackageGrpcClient.getOptionPriceCurrency(10L, 20L))
                .thenReturn(new SubscriptionPackageGrpcClient.OptionPriceCurrency(25.00, "AZN", 1));
        when(paymentStore.buildPackageDescription(10L, 20L, "Test Payment"))
                .thenReturn("Test Payment,packageId:10,optionId:20");

        Payment pending = new Payment();
        pending.setStatus(BobPaymentStore.STATUS_PENDING);
        pending.setCurrency("AZN");
        pending.setTransactionId("BOB_TX");
        pending.setDescription("Test Payment,packageId:10,optionId:20");
        when(paymentStore.createPending(eq(userId), anyString(), eq(25.00), eq("AZN"),
                anyString(), eq(true), isNull(), isNull(), eq("BOB_PAYMENT"))).thenReturn(pending);

        Map<String, Object> bankResponse = new HashMap<>();
        bankResponse.put("errorCode", "0");
        bankResponse.put("orderId", "BOB_ORDER_123");
        bankResponse.put("formUrl", "https://epg.bankofbaku.com/payment/page?orderId=BOB_ORDER_123");
        when(bobRestClient.registerOrder(anyString(), anyDouble(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(bankResponse);

        BobInitiateResponse response = bobIntegrationService.initiatePayment(userId, request);

        assertEquals("BOB_ORDER_123", response.getOrderId());
        assertEquals("BOB", response.getProvider());
        verify(paymentStore).markRegistered(pending, "BOB_ORDER_123",
                "https://epg.bankofbaku.com/payment/page?orderId=BOB_ORDER_123");
    }

    @Test
    void testInitiatePayment_MaintenanceMode() {
        when(bobProperties.isMaintenanceMode()).thenReturn(true);
        BobInitiateRequest request = BobInitiateRequest.builder().packageId(10L).optionId(20L).build();
        assertThrows(BobMaintenanceException.class, () ->
                bobIntegrationService.initiatePayment(1L, request));
    }

    @Test
    void testPayWithSavedCard_Success() {
        Long userId = 1L;
        String cardId = "BINDING_999";
        UserCard userCard = UserCard.builder().userId(userId).cardId(cardId).cardMask("411111****1111").build();
        when(bobCardService.requireSavedCard(userId, cardId)).thenReturn(userCard);
        when(subscriptionPackageGrpcClient.getOptionPriceCurrency(10L, 20L))
                .thenReturn(new SubscriptionPackageGrpcClient.OptionPriceCurrency(15.00, "AZN", 1));

        Payment pending = new Payment();
        pending.setDescription("FitNest Saved Card Payment");
        when(paymentStore.createPending(eq(userId), anyString(), eq(15.00), eq("AZN"),
                anyString(), eq(false), eq(cardId), eq("411111****1111"), eq("SAVED_CARD"))).thenReturn(pending);

        Map<String, Object> registerResp = Map.of("errorCode", "0", "orderId", "ORDER_BIND_123");
        when(bobRestClient.registerOrder(anyString(), anyDouble(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(registerResp);
        when(bobRestClient.payWithBinding("ORDER_BIND_123", cardId)).thenReturn(Map.of("errorCode", "0"));

        BobOrderStatusResponse statusResponse = BobOrderStatusResponse.builder().orderStatus(2).rrn("RRN123").build();
        when(bobRestClient.getOrderStatusExtended("ORDER_BIND_123")).thenReturn(statusResponse);
        when(statusMapper.toBobStatus(2)).thenReturn(az.fitnest.payment.model.enums.BobPaymentStatus.APPROVED);

        BobInitiateResponse response = bobIntegrationService.payWithSavedCard(userId,
                BobPayWithSavedCardRequest.builder().cardId(cardId).packageId(10L).optionId(20L).build());

        assertEquals("ORDER_BIND_123", response.getOrderId());
        verify(paymentSubscriptionService).assign(userId, 10L, 20L, true);
        verify(paymentStore).markSuccess(pending, statusResponse);
    }

    @Test
    void testProcessCallback_Success() {
        String orderId = "BOB_ORDER_123";
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setTransactionId("BOB_TX_123");
        payment.setUserId(1L);
        payment.setCallbackProcessed(false);

        when(paymentStore.findByOrderIdOrTransactionId(orderId, null)).thenReturn(Optional.of(payment));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        BobOrderStatusResponse statusResponse = BobOrderStatusResponse.builder()
                .orderStatus(2)
                .rrn("987654321")
                .pan("411111****9999")
                .bindingId("NEW_BINDING_555")
                .build();
        when(bobRestClient.getOrderStatusExtended(orderId)).thenReturn(statusResponse);
        when(statusMapper.toBobStatus(2)).thenReturn(az.fitnest.payment.model.enums.BobPaymentStatus.APPROVED);

        String redirectUrl = bobIntegrationService.processCallback(null, orderId);

        assertEquals("https://fitnest.az/payment/success", redirectUrl);
        assertEquals(BobPaymentStore.STATUS_SUCCESS, payment.getStatus());
        assertTrue(payment.getCallbackProcessed());
        verify(bobCardService).checkAndSaveUserCard(payment, statusResponse);
        verify(paymentSubscriptionService).assignFromPaymentDescription(payment);
    }

    @Test
    void testProcessCallback_DeclineStoresRobustMessage() {
        Payment payment = new Payment();
        payment.setOrderId("ORDER_FAIL");
        payment.setTransactionId("BOB_TX_FAIL");
        payment.setCallbackProcessed(false);

        when(paymentStore.findByOrderIdOrTransactionId("ORDER_FAIL", null)).thenReturn(Optional.of(payment));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        BobOrderStatusResponse statusResponse = BobOrderStatusResponse.builder()
                .orderStatus(6)
                .errorMessage("Success")
                .actionCode("-2006")
                .build();
        when(bobRestClient.getOrderStatusExtended("ORDER_FAIL")).thenReturn(statusResponse);
        when(statusMapper.toBobStatus(6)).thenReturn(az.fitnest.payment.model.enums.BobPaymentStatus.DECLINED);
        when(statusMapper.declineMessage(statusResponse)).thenReturn("Ödənişdən imtina edildi (actionCode=-2006)");
        when(statusMapper.operationCode(statusResponse)).thenReturn("-2006");

        String redirect = bobIntegrationService.processCallback(null, "ORDER_FAIL");

        assertEquals("https://fitnest.az/payment/error", redirect);
        verify(paymentStore).markFailed(eq(payment),
                eq("Ödənişdən imtina edildi (actionCode=-2006)"),
                eq("-2006"),
                contains("orderStatus=6"));
    }

    @Test
    void testInitiatePayment_InstallmentSetsType() {
        Long userId = 1L;
        BobInitiateRequest request = BobInitiateRequest.builder()
                .packageId(10L)
                .optionId(20L)
                .installmentMonths(6)
                .description("Gold")
                .build();

        when(subscriptionPackageGrpcClient.getOptionPriceCurrency(10L, 20L))
                .thenReturn(new SubscriptionPackageGrpcClient.OptionPriceCurrency(726.00, "AZN", 1));
        when(paymentStore.buildPackageDescription(10L, 20L, "Gold"))
                .thenReturn("Gold,packageId:10,optionId:20");

        Payment pending = new Payment();
        pending.setStatus(BobPaymentStore.STATUS_PENDING);
        pending.setCurrency("AZN");
        pending.setDescription("Gold,packageId:10,optionId:20");
        when(paymentStore.createPending(eq(userId), anyString(), eq(726.00), eq("AZN"),
                anyString(), eq(false), isNull(), isNull(), eq("BOB_INSTALLMENT"))).thenReturn(pending);

        Map<String, Object> bankResponse = new HashMap<>();
        bankResponse.put("errorCode", "0");
        bankResponse.put("orderId", "BOB_ORDER_INST");
        bankResponse.put("formUrl", "https://epg.bankofbaku.com/payment/page?orderId=BOB_ORDER_INST");
        when(bobRestClient.registerOrder(anyString(), anyDouble(), anyString(), anyString(), anyString(), isNull(), eq(6)))
                .thenReturn(bankResponse);

        BobInitiateResponse response = bobIntegrationService.initiatePayment(userId, request);

        assertEquals("BOB_ORDER_INST", response.getOrderId());
        verify(paymentStore).createPending(eq(userId), anyString(), eq(726.00), eq("AZN"),
                anyString(), eq(false), isNull(), isNull(), eq("BOB_INSTALLMENT"));
        verify(bobRestClient).registerOrder(anyString(), eq(726.00), anyString(), anyString(), anyString(), isNull(), eq(6));
    }

    @Test
    void testCheckPaymentStatus_MarksFailedOnDecline() {
        Payment payment = new Payment();
        payment.setOrderId("ORDER_DECLINED");
        payment.setStatus(BobPaymentStore.STATUS_PENDING);
        payment.setTransactionId("BOB_TX");

        BobOrderStatusResponse statusResponse = BobOrderStatusResponse.builder()
                .orderStatus(6)
                .errorCode("0")
                .errorMessage("Success")
                .actionCode("-2006")
                .build();

        when(bobRestClient.getOrderStatusExtended("ORDER_DECLINED")).thenReturn(statusResponse);
        when(paymentStore.findByOrderIdOrTransactionId("ORDER_DECLINED", "ORDER_DECLINED"))
                .thenReturn(Optional.of(payment));
        when(statusMapper.isApproved(6)).thenReturn(false);
        when(statusMapper.isTerminalFailure(6)).thenReturn(true);
        when(statusMapper.declineMessage(statusResponse)).thenReturn("Ödənişdən imtina edildi (actionCode=-2006)");
        when(statusMapper.operationCode(statusResponse)).thenReturn("-2006");

        BobOrderStatusResponse result = bobIntegrationService.checkPaymentStatus("ORDER_DECLINED");

        assertNotNull(result);
        verify(statusMapper).enrichStatusResponse(statusResponse, payment);
        verify(paymentStore).markFailed(eq(payment),
                eq("Ödənişdən imtina edildi (actionCode=-2006)"),
                eq("-2006"),
                contains("orderStatus=6"));
        verify(paymentSubscriptionService, never()).assignFromPaymentDescription(any());
        verify(bobCardService, never()).checkAndSaveUserCard(any(), any());
    }

    @Test
    void testDeleteSavedCard_Success() {
        bobIntegrationService.deleteSavedCard(1L, "BIND_123");
        verify(bobCardService).deleteSavedCard(1L, "BIND_123");
    }
}
