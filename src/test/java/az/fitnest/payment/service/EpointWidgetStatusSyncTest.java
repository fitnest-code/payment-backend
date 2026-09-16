package az.fitnest.payment.service;

import az.fitnest.payment.client.epoint.EpointProperties;
import az.fitnest.payment.client.epoint.EpointService;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.dto.epoint.EpointResponse;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.CallbackLogRepository;
import az.fitnest.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpointWidgetStatusSyncTest {

    @Mock
    private EpointService epointService;
    @Mock
    private EpointSigner signer;
    @Mock
    private EpointProperties epointProperties;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private CallbackLogRepository callbackLogRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private PaymentSubscriptionService paymentSubscriptionService;

    @InjectMocks
    private EpointIntegrationService integrationService;

    private Payment widgetPayment;

    @BeforeEach
    void setUp() {
        widgetPayment = new Payment();
        widgetPayment.setId(11L);
        widgetPayment.setProvider("EPOINT");
        widgetPayment.setType("WIDGET_PAYMENT");
        widgetPayment.setStatus("PENDING_USER_ACTION");
        widgetPayment.setOrderId("3874721e-3adb-4761-bf85-6b32c5f1990a");
        widgetPayment.setTransactionId("tw0022240181");
        widgetPayment.setAmount(0.10);
        widgetPayment.setCurrency("AZN");
        widgetPayment.setUserId(27L);
        widgetPayment.setDescription("packageId:1,optionId:1,device:ios,type:One-time payment");
        widgetPayment.setCallbackProcessed(false);
    }

    @Test
    void getStatusRetriesNineDigitTwAfterTenDigitServerError() {
        widgetPayment.setRrn("keep-me");
        when(paymentRepository.findByOrderId("3874721e-3adb-4761-bf85-6b32c5f1990a"))
                .thenReturn(Optional.of(widgetPayment));
        when(epointService.getStatus("tw0022240181")).thenReturn(
                EpointResponse.builder().status("server_error").code("500").build());
        when(epointService.getStatus("tw022240181")).thenReturn(
                EpointResponse.builder()
                        .status("success")
                        .transaction("tw022240181")
                        .orderId("3874721e-3adb-4761-bf85-6b32c5f1990a")
                        .amount(0.10)
                        .rrn("999")
                        .bankTransaction("bank-1")
                        .build());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EpointResponse response = integrationService.getStatus("3874721e-3adb-4761-bf85-6b32c5f1990a");

        assertEquals("success", response.status());
        assertEquals("SUCCESS", widgetPayment.getStatus());
        assertEquals("tw022240181", widgetPayment.getTransactionId());
        assertEquals("999", widgetPayment.getRrn());
        assertEquals("bank-1", widgetPayment.getBankTransaction());
        verify(paymentSubscriptionService).assignFromPaymentDescription(
                eq(widgetPayment), eq(27L), any());
    }

    @Test
    void processCallbackMatchesAlternateTwPaddingWhenOrderIdMissing() throws Exception {
        when(epointProperties.getPrivateKey()).thenReturn("key");
        when(signer.verify(anyString(), anyString(), any())).thenReturn(true);
        when(signer.decodeData(eq("payload"), eq(EpointResponse.class))).thenReturn(
                EpointResponse.builder()
                        .status("success")
                        .transaction("tw022240181")
                        .amount(0.1)
                        .rrn("888")
                        .bankTransaction("bank-2")
                        .build());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(paymentRepository.findByTransactionIdForUpdate(anyString())).thenReturn(Optional.empty());
        when(paymentRepository.findByTransactionIdForUpdate("tw0022240181"))
                .thenReturn(Optional.of(widgetPayment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        integrationService.processCallback("payload", "sig");

        assertEquals("SUCCESS", widgetPayment.getStatus());
        assertEquals(Boolean.TRUE, widgetPayment.getCallbackProcessed());
        verify(paymentSubscriptionService).assignFromPaymentDescription(
                eq(widgetPayment), eq(27L), any());
        verify(paymentRepository, never()).findByOrderId(any());
    }

    @Test
    void getStatusKeepsTwPrefixWhenEpointReportsTeForSameToken() {
        widgetPayment.setTransactionId("tw022241588");
        when(paymentRepository.findByOrderId("3874721e-3adb-4761-bf85-6b32c5f1990a"))
                .thenReturn(Optional.of(widgetPayment));
        when(epointService.getStatus("tw022241588")).thenReturn(
                EpointResponse.builder()
                        .status("success")
                        .transaction("te022241588")
                        .orderId("3874721e-3adb-4761-bf85-6b32c5f1990a")
                        .amount(0.10)
                        .rrn("625020306717")
                        .cardMask("************0239")
                        .build());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        integrationService.getStatus("3874721e-3adb-4761-bf85-6b32c5f1990a");

        assertEquals("SUCCESS", widgetPayment.getStatus());
        assertEquals("tw022241588", widgetPayment.getTransactionId());
        assertEquals("625020306717", widgetPayment.getRrn());
    }
}
