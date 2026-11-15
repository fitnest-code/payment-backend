package az.fitnest.payment.grpc;

import az.fitnest.payment.dto.abb.AbbInitiateResponse;
import az.fitnest.payment.dto.bob.BobInitiateResponse;
import az.fitnest.payment.service.AbbIntegrationService;
import az.fitnest.payment.service.BobIntegrationService;
import az.fitnest.payment.service.EpointIntegrationService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentGrpcServiceInstallmentTest {

    @Mock EpointIntegrationService epoint;
    @Mock AbbIntegrationService abb;
    @Mock BobIntegrationService bob;
    @Mock StreamObserver<CreatePaymentResponse> observer;
    // Remaining constructor collaborators are unused by the installment path.
    @Mock az.fitnest.payment.service.UserPaymentService userPaymentService;
    @Mock az.fitnest.payment.client.SubscriptionPackageGrpcClient packageClient;
    @Mock az.fitnest.payment.service.CoinWalletService coinWalletService;
    @Mock az.fitnest.payment.repository.PaymentRepository paymentRepository;
    @InjectMocks PaymentGrpcService service;

    private static CreatePaymentRequest.Builder bf(String provider) {
        return CreatePaymentRequest.newBuilder()
                .setOrderId("17000000000001234").setAmount(1874.0).setCurrency("AZN")
                .setDescription("Premium - 12 months").setPrePriced(true).setUserId(7L)
                .setCoinsUsed(16.0).setProvider(provider).setInstallmentMonths(6)
                .addOtherAttr("packageId:4,optionId:102,campaignId:cmp-1");
    }

    @Test
    void abbUsesProvidedAmountOrderIdAndCampaign() {
        when(abb.initiateInstallmentPaymentPrePriced(anyLong(), anyLong(), anyLong(), anyInt(), anyDouble(),
                any(), any(), any(), any())).thenReturn(AbbInitiateResponse.success("https://abb/pay", "17000000000001234"));

        service.createPayment(bf("abb").build(), observer);

        verify(abb).initiateInstallmentPaymentPrePriced(eq(7L), eq(4L), eq(102L), eq(6), eq(1874.0), eq("AZN"),
                eq("17000000000001234"), eq("cmp-1"), eq(new BigDecimal("16.00")));
        ArgumentCaptor<CreatePaymentResponse> c = ArgumentCaptor.forClass(CreatePaymentResponse.class);
        verify(observer).onNext(c.capture());
        assertEquals("success", c.getValue().getStatus());
        assertEquals("https://abb/pay", c.getValue().getRedirectUrl());
        verifyNoInteractions(epoint, bob);
    }

    @Test
    void bobUsesClientOrderIdAndReturnsFormUrl() {
        when(bob.initiateInstallmentPaymentPrePriced(anyLong(), anyLong(), anyLong(), anyInt(), anyDouble(),
                any(), any(), any(), any())).thenReturn(BobInitiateResponse.builder()
                .orderId("bank-uuid").formUrl("https://bob/pay").build());

        service.createPayment(bf("BOB").setOrderId("ord_a1b2c3d4").build(), observer);

        verify(bob).initiateInstallmentPaymentPrePriced(eq(7L), eq(4L), eq(102L), eq(6), eq(1874.0), eq("AZN"),
                eq("ord_a1b2c3d4"), eq("cmp-1"), eq(new BigDecimal("16.00")));
        ArgumentCaptor<CreatePaymentResponse> c = ArgumentCaptor.forClass(CreatePaymentResponse.class);
        verify(observer).onNext(c.capture());
        assertEquals("https://bob/pay", c.getValue().getRedirectUrl());
        verifyNoInteractions(epoint, abb);
    }

    @Test
    void applePayPrePricedUsesBfAmountAndReturnsEpointPaymentId() {
        when(epoint.createApplePayPaymentPrePriced(anyLong(), anyLong(), anyLong(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(new az.fitnest.payment.dto.epoint.EpointTokenResponse(
                        "success", "epoint-pay-123", null, null, null, null));

        service.createPayment(bf("Apple_Pay").setOrderId("ord_a1b2c3d4").build(), observer);

        verify(epoint).createApplePayPaymentPrePriced(eq(7L), eq(4L), eq(102L), eq(1874.0), eq("AZN"),
                eq("ord_a1b2c3d4"), eq("cmp-1"), eq(new BigDecimal("16.00")));
        ArgumentCaptor<CreatePaymentResponse> c = ArgumentCaptor.forClass(CreatePaymentResponse.class);
        verify(observer).onNext(c.capture());
        assertEquals("success", c.getValue().getStatus());
        assertEquals("epoint-pay-123", c.getValue().getTransactionId());
        assertEquals("", c.getValue().getRedirectUrl());
        verifyNoInteractions(abb, bob);
    }

    @Test
    void applePayWithoutPrePricedIsInvalidArgument() {
        service.createPayment(bf("apple_pay").setPrePriced(false).build(), observer);
        verify(observer).onError(any());
        verifyNoInteractions(abb, bob, epoint);
    }

    @Test
    void googlePayPrePricedUsesBfAmountAndReturnsEpointPaymentId() {
        when(epoint.createGooglePayPaymentPrePriced(anyLong(), anyLong(), anyLong(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(new az.fitnest.payment.dto.epoint.EpointTokenResponse(
                        "success", "epoint-pay-456", null, null, null, null));

        service.createPayment(bf("Google_Pay").setOrderId("ord_g1b2c3d4").build(), observer);

        verify(epoint).createGooglePayPaymentPrePriced(eq(7L), eq(4L), eq(102L), eq(1874.0), eq("AZN"),
                eq("ord_g1b2c3d4"), eq("cmp-1"), eq(new BigDecimal("16.00")));
        verify(epoint, never()).createGooglePayPayment(anyLong(), anyLong(), anyLong());
        ArgumentCaptor<CreatePaymentResponse> c = ArgumentCaptor.forClass(CreatePaymentResponse.class);
        verify(observer).onNext(c.capture());
        assertEquals("success", c.getValue().getStatus());
        assertEquals("epoint-pay-456", c.getValue().getTransactionId());
        assertEquals("", c.getValue().getRedirectUrl());
        verifyNoInteractions(abb, bob);
    }

    @Test
    void googlePayWithoutPrePricedIsInvalidArgument() {
        service.createPayment(bf("google_pay").setPrePriced(false).build(), observer);
        verify(observer).onError(any());
        verifyNoInteractions(abb, bob, epoint);
    }

    private static az.fitnest.payment.dto.epoint.EpointResponse epointResponse(String status, String redirect) {
        return az.fitnest.payment.dto.epoint.EpointResponse.builder()
                .status(status).transaction("te001").redirectUrl(redirect).build();
    }

    @Test
    void savedCardPrePricedChargesBfAmountWithoutRequote() {
        when(epoint.executePayWithCardPrePriced(anyLong(), any(), any(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(epointResponse("success", null));

        service.createPayment(bf("Saved_Card").setOrderId("ord_s1b2c3d4").setCardId("card-9").build(), observer);

        verify(epoint).executePayWithCardPrePriced(eq(7L), eq("card-9"), eq("ord_s1b2c3d4"), eq(1874.0), eq("AZN"),
                eq("packageId:4,optionId:102,campaignId:cmp-1,type:One-time payment"),
                eq(new BigDecimal("16.00")), eq(false));
        verify(epoint, never()).executePayWithCard(anyLong(), any(), anyLong(), anyLong(), any(), any());
        ArgumentCaptor<CreatePaymentResponse> c = ArgumentCaptor.forClass(CreatePaymentResponse.class);
        verify(observer).onNext(c.capture());
        assertEquals("success", c.getValue().getStatus());
        assertEquals("te001", c.getValue().getTransactionId());
        assertEquals("", c.getValue().getRedirectUrl());
        verifyNoInteractions(abb, bob);
    }

    @Test
    void savedCardPassesThrough3dsRedirect() {
        when(epoint.executePayWithCardPrePriced(anyLong(), any(), any(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(epointResponse("new", "https://epoint/3ds"));

        service.createPayment(bf("saved_card").setCardId("card-9").build(), observer);

        ArgumentCaptor<CreatePaymentResponse> c = ArgumentCaptor.forClass(CreatePaymentResponse.class);
        verify(observer).onNext(c.capture());
        assertEquals("new", c.getValue().getStatus());
        assertEquals("https://epoint/3ds", c.getValue().getRedirectUrl());
    }

    @Test
    void savedCardWithoutCardIdIsInvalidArgument() {
        service.createPayment(bf("saved_card").build(), observer);
        verify(observer).onError(any());
        verifyNoInteractions(abb, bob, epoint);
    }

    @Test
    void savedCardWithoutPrePricedIsInvalidArgument() {
        service.createPayment(bf("saved_card").setCardId("card-9").setPrePriced(false).build(), observer);
        verify(observer).onError(any());
        verifyNoInteractions(abb, bob, epoint);
    }

    @Test
    void installmentWithoutMonthsIsInvalidArgument() {
        service.createPayment(bf("abb").setInstallmentMonths(0).build(), observer);
        verify(observer).onError(any());
        verifyNoInteractions(abb, bob, epoint);
    }
}
