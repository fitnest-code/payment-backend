package az.fitnest.payment.controller;

import az.fitnest.payment.dto.coin.CalculateDiscountRequest;
import az.fitnest.payment.dto.coin.CalculateDiscountResponse;
import az.fitnest.payment.dto.coin.CoinWalletResponse;
import az.fitnest.payment.service.CoinWalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @DisplayName("GET /api/v1/coins/wallet - Wallet info qaytarır")
    void testGetWalletInfo() {
        CoinWalletResponse expected = CoinWalletResponse.builder()
                .totalBalance(new BigDecimal("50.00"))
                .aznEquivalent(new BigDecimal("2.50"))
                .expiringSoonCoins(BigDecimal.ZERO)
                .build();

        when(coinWalletService.getWalletInfo(any())).thenReturn(expected);

        ResponseEntity<CoinWalletResponse> response = coinWalletController.getWalletInfo();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(new BigDecimal("50.00"), response.getBody().getTotalBalance());
    }

    @Test
    @DisplayName("POST /api/v1/coins/calculate-discount - Endirim hesablayır")
    void testCalculateDiscount() {
        CalculateDiscountRequest request = CalculateDiscountRequest.builder()
                .originalPrice(new BigDecimal("100.00"))
                .coinsToUse(new BigDecimal("40.00"))
                .build();

        CalculateDiscountResponse expected = CalculateDiscountResponse.builder()
                .originalPrice(new BigDecimal("100.00"))
                .coinsToUse(new BigDecimal("40.00"))
                .appliedDiscountAzn(new BigDecimal("2.00"))
                .finalPaymentAmount(new BigDecimal("98.00"))
                .maxDiscountLimitAzn(new BigDecimal("20.00"))
                .isMaxDiscountReached(false)
                .build();

        when(coinWalletService.calculateCheckoutDiscount(any(), eq(new BigDecimal("100.00")), eq(new BigDecimal("40.00"))))
                .thenReturn(expected);

        ResponseEntity<CalculateDiscountResponse> response = coinWalletController.calculateDiscount(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(new BigDecimal("2.00"), response.getBody().getAppliedDiscountAzn());
        assertEquals(new BigDecimal("98.00"), response.getBody().getFinalPaymentAmount());
    }
}
