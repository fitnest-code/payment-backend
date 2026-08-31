package az.fitnest.payment.controller;

import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.dto.common.PaginatedResponse;
import az.fitnest.payment.model.enums.*;
import az.fitnest.payment.service.CoinWalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoinWalletControllerTest {

    @Mock
    private CoinWalletService coinWalletService;

    @InjectMocks
    private CoinWalletController coinWalletController;

    @Test
    @DisplayName("GET /api/v1/coins/wallet - Wallet info qaytarır (PM Arif-in response modeli)")
    void testGetWalletInfo() {
        CoinWalletResponse expected = CoinWalletResponse.builder()
                .totalBalance(new BigDecimal("150.00"))
                .aznEquivalent(new BigDecimal("7.50"))
                .firstCoinEarnedAt(LocalDateTime.of(2026, 12, 20, 10, 0))
                .expiryDate(LocalDateTime.of(2027, 12, 20, 10, 0))
                .daysUntilExpiry(116L)
                .build();

        when(coinWalletService.getWalletInfo(any())).thenReturn(expected);

        ResponseEntity<CoinWalletResponse> response = coinWalletController.getWalletInfo();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(new BigDecimal("150.00"), response.getBody().getTotalBalance());
        assertEquals(116L, response.getBody().getDaysUntilExpiry());
    }

    @Test
    @DisplayName("GET /api/v1/coins/history - Tarixçə qaytarır")
    void testGetTransactionHistory() {
        CoinTransactionResponse tx = CoinTransactionResponse.builder()
                .id(1L)
                .type(CoinTransactionType.BONUS)
                .sourceType(CoinTransactionSourceType.WELCOME_BONUS)
                .sourceTitle("Qeydiyyat bonusu")
                .amount(new BigDecimal("50.00"))
                .balanceAfter(new BigDecimal("50.00"))
                .createdDate(LocalDateTime.now())
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        when(coinWalletService.getTransactionHistory(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(tx), pageable, 1));

        ResponseEntity<PaginatedResponse<CoinTransactionResponse>> response = coinWalletController.getTransactionHistory(CoinTransactionCategory.ALL, pageable);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().items().size());
    }

    @Test
    @DisplayName("POST /api/v1/coins/checkout/preview - Endirim və Preview hesablayır (ON/OFF Toggle modeli)")
    void testCalculateDiscount() {
        CalculateDiscountRequest request = CalculateDiscountRequest.builder()
                .subscriptionPlanId(123L)
                .originalPrice(new BigDecimal("50.00"))
                .useCoin(true)
                .build();

        CheckoutPlanInfo plan = CheckoutPlanInfo.builder().id(123L).name("Bronze").duration("1 ay").build();
        CheckoutCoinInfo coin = CheckoutCoinInfo.builder()
                .availableBalance(new BigDecimal("320.00"))
                .availableAzn(new BigDecimal("16.00"))
                .appliedCoins(new BigDecimal("320.00"))
                .discountAzn(new BigDecimal("16.00"))
                .build();

        CheckoutDiscountItem coinDiscountItem = CheckoutDiscountItem.builder()
                .type("COIN")
                .amount(new BigDecimal("16.00"))
                .coinsUsed(new BigDecimal("320.00"))
                .description("FitNest Coin endirimi")
                .build();

        CalculateDiscountResponse expected = CalculateDiscountResponse.builder()
                .plan(plan)
                .originalPrice(new BigDecimal("50.00"))
                .discounts(List.of(coinDiscountItem))
                .totalDiscountAmount(new BigDecimal("16.00"))
                .coin(coin)
                .finalPaymentAmount(new BigDecimal("34.00"))
                .isFullCoinPaymentAvailable(false)
                .build();

        when(coinWalletService.calculateCheckoutDiscount(any(), eq(request)))
                .thenReturn(expected);

        ResponseEntity<CalculateDiscountResponse> response = coinWalletController.calculateDiscount(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(new BigDecimal("16.00"), response.getBody().getCoin().getDiscountAzn());
        assertEquals(new BigDecimal("34.00"), response.getBody().getFinalPaymentAmount());
    }

    @Test
    @DisplayName("POST /api/v1/coins/pay-full - 100% Coin ilə ödəniş")
    void testPayFullWithCoins() {
        PayFullWithCoinsRequest request = PayFullWithCoinsRequest.builder()
                .subscriptionPlanId(123L)
                .originalPrice(new BigDecimal("10.00"))
                .build();

        PayFullWithCoinsResponse expected = PayFullWithCoinsResponse.builder()
                .success(true)
                .orderId("COIN-ORD-A1B2C3D4")
                .subscriptionPlanId(123L)
                .coinsDeducted(new BigDecimal("200.00"))
                .remainingBalance(new BigDecimal("100.00"))
                .message("Ödəniş tam olaraq Coin ilə həyata keçirildi")
                .build();

        when(coinWalletService.payFullWithCoins(any(), eq(request))).thenReturn(expected);

        ResponseEntity<PayFullWithCoinsResponse> response = coinWalletController.payFullWithCoins(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().getSuccess());
        assertEquals("COIN-ORD-A1B2C3D4", response.getBody().getOrderId());
    }

}

