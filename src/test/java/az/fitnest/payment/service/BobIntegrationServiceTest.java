package az.fitnest.payment.service;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.UserSubscriptionGrpcClient;
import az.fitnest.payment.client.bob.BobProperties;
import az.fitnest.payment.client.bob.BobRestClient;
import az.fitnest.payment.dto.bob.*;
import az.fitnest.payment.exception.BobMaintenanceException;
import az.fitnest.payment.exception.BobPaymentException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.List;
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
    private PaymentRepository paymentRepository;

    @Mock
    private UserCardRepository userCardRepository;

    @Mock
    private SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    @Mock
    private UserSubscriptionGrpcClient userSubscriptionGrpcClient;

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

        SubscriptionPackageGrpcClient.OptionPriceCurrency priceCurrency =
                new SubscriptionPackageGrpcClient.OptionPriceCurrency(25.00, "AZN", 1);

        when(subscriptionPackageGrpcClient.getOptionPriceCurrency(10L, 20L)).thenReturn(priceCurrency);

        Map<String, Object> bankResponse = new HashMap<>();
        bankResponse.put("errorCode", "0");
        bankResponse.put("orderId", "BOB_ORDER_123");
        bankResponse.put("formUrl", "https://epg.bankofbaku.com/payment/page?orderId=BOB_ORDER_123");

        when(bobRestClient.registerOrder(anyString(), anyDouble(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(bankResponse);

        BobInitiateResponse response = bobIntegrationService.initiatePayment(userId, request);

        assertNotNull(response);
        assertEquals("BOB_ORDER_123", response.getOrderId());
        assertEquals("https://epg.bankofbaku.com/payment/page?orderId=BOB_ORDER_123", response.getFormUrl());
        assertEquals("BOB", response.getProvider());

        verify(paymentRepository, times(2)).save(any(Payment.class));
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

        UserCard userCard = UserCard.builder()
                .userId(userId)
                .cardId(cardId)
                .cardMask("411111****1111")
                .build();

        when(userCardRepository.findByUserIdAndCardId(userId, cardId)).thenReturn(Optional.of(userCard));

        SubscriptionPackageGrpcClient.OptionPriceCurrency priceCurrency =
                new SubscriptionPackageGrpcClient.OptionPriceCurrency(15.00, "AZN", 1);

        when(subscriptionPackageGrpcClient.getOptionPriceCurrency(10L, 20L)).thenReturn(priceCurrency);

        Map<String, Object> registerResp = new HashMap<>();
        registerResp.put("errorCode", "0");
        registerResp.put("orderId", "ORDER_BIND_123");

        when(bobRestClient.registerOrder(anyString(), anyDouble(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(registerResp);

        Map<String, Object> payResp = new HashMap<>();
        payResp.put("errorCode", "0");
        when(bobRestClient.payWithBinding("ORDER_BIND_123", cardId)).thenReturn(payResp);

        BobOrderStatusResponse statusResponse = BobOrderStatusResponse.builder()
                .orderStatus(2)
                .rrn("RRN123456")
                .pan("411111****1111")
                .build();

        when(bobRestClient.getOrderStatusExtended("ORDER_BIND_123")).thenReturn(statusResponse);

        BobPayWithSavedCardRequest request = BobPayWithSavedCardRequest.builder()
                .cardId(cardId)
                .packageId(10L)
                .optionId(20L)
                .build();

        BobInitiateResponse response = bobIntegrationService.payWithSavedCard(userId, request);

        assertNotNull(response);
        assertEquals("ORDER_BIND_123", response.getOrderId());
        verify(userSubscriptionGrpcClient, times(1)).assignSubscriptionToUser(userId, 10L, 20L, true);
    }

    @Test
    void testProcessCallback_Success() {
        String orderId = "BOB_ORDER_123";
        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setTransactionId("BOB_TX_123");
        payment.setUserId(1L);
        payment.setCallbackProcessed(false);

        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        BobOrderStatusResponse statusResponse = BobOrderStatusResponse.builder()
                .orderStatus(2)
                .rrn("987654321")
                .pan("411111****9999")
                .bindingId("NEW_BINDING_555")
                .build();

        when(bobRestClient.getOrderStatusExtended(orderId)).thenReturn(statusResponse);

        String redirectUrl = bobIntegrationService.processCallback(null, orderId);

        assertEquals("https://fitnest.az/payment/success", redirectUrl);
        assertEquals("SUCCESS", payment.getStatus());
        assertTrue(payment.getCallbackProcessed());
        verify(userCardRepository, times(1)).save(any(UserCard.class));
    }

    @Test
    void testDeleteSavedCard_Success() {
        Long userId = 1L;
        String cardId = "BIND_123";

        UserCard card = UserCard.builder().userId(userId).cardId(cardId).build();
        when(userCardRepository.findByUserIdAndCardId(userId, cardId)).thenReturn(Optional.of(card));

        bobIntegrationService.deleteSavedCard(userId, cardId);

        verify(bobRestClient, times(1)).unbindCard(cardId);
        verify(userCardRepository, times(1)).delete(card);
    }
}
