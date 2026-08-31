package az.fitnest.payment.controller;

import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.dto.common.PaginatedResponse;
import az.fitnest.payment.model.enums.CoinTransactionType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoinAdminControllerTest {

    @Mock
    private CoinWalletService coinWalletService;

    @InjectMocks
    private CoinAdminController coinAdminController;

    @Test
    @DisplayName("GET /api/v1/admin/coins/settings - Cari tənzimləmələri qaytarır")
    void testGetSettings() {
        CoinSettingsResponse settings = CoinSettingsResponse.builder()
                .welcomeBonusAmount(new BigDecimal("50.00"))
                .earnRateAznToCoin(new BigDecimal("1.00"))
                .spendRateCoinToAzn(new BigDecimal("20.00"))
                .maxDiscountPercentage(new BigDecimal("100.00"))
                .expiryMonths(12)
                .active(true)
                .build();

        when(coinWalletService.getSettings()).thenReturn(settings);

        ResponseEntity<CoinSettingsResponse> response = coinAdminController.getSettings();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(new BigDecimal("50.00"), response.getBody().getWelcomeBonusAmount());
    }

    @Test
    @DisplayName("PUT /api/v1/admin/coins/settings - Tənzimləmələri yeniləyir")
    void testUpdateSettings() {
        CoinSettingsRequest request = CoinSettingsRequest.builder()
                .welcomeBonusAmount(new BigDecimal("60.00"))
                .earnRateAznToCoin(new BigDecimal("1.00"))
                .spendRateCoinToAzn(new BigDecimal("20.00"))
                .maxDiscountPercentage(new BigDecimal("100.00"))
                .expiryMonths(12)
                .active(true)
                .build();

        CoinSettingsResponse expected = CoinSettingsResponse.builder()
                .welcomeBonusAmount(new BigDecimal("60.00"))
                .active(true)
                .build();

        when(coinWalletService.updateSettings(eq(request))).thenReturn(expected);

        ResponseEntity<CoinSettingsResponse> response = coinAdminController.updateSettings(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(new BigDecimal("60.00"), response.getBody().getWelcomeBonusAmount());
    }

    @Test
    @DisplayName("POST /api/v1/admin/coins/adjust - Manual Coin korreksiyası (Credit/Debit)")
    void testAdjustCoins() {
        ManualCoinAdjustRequest request = ManualCoinAdjustRequest.builder()
                .userId(100L)
                .amount(new BigDecimal("50.00"))
                .type(CoinTransactionType.ADJUSTMENT)
                .description("Bonus")
                .sendNotification(true)
                .build();

        CoinWalletResponse expected = CoinWalletResponse.builder()
                .totalBalance(new BigDecimal("100.00"))
                .build();

        when(coinWalletService.manualAdjustCoins(eq(request))).thenReturn(expected);

        ResponseEntity<CoinWalletResponse> response = coinAdminController.adjustCoins(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(new BigDecimal("100.00"), response.getBody().getTotalBalance());
    }

    @Test
    @DisplayName("POST /api/v1/admin/coins/bulk-adjust - Toplu Coin artırılması")
    void testBulkAdjustCoins() {
        BulkCoinAdjustRequest request = BulkCoinAdjustRequest.builder()
                .userIds(List.of(101L, 102L))
                .amount(new BigDecimal("50.00"))
                .type(CoinTransactionType.BONUS)
                .description("Bulk bonus")
                .sendNotification(true)
                .build();

        BulkCoinAdjustResponse expected = BulkCoinAdjustResponse.builder()
                .totalRequested(2)
                .totalSuccess(2)
                .totalFailed(0)
                .successUserIds(List.of(101L, 102L))
                .failedUserIds(List.of())
                .build();

        when(coinWalletService.bulkAdjustCoins(eq(request))).thenReturn(expected);

        ResponseEntity<BulkCoinAdjustResponse> response = coinAdminController.bulkAdjustCoins(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().getTotalSuccess());
    }

    @Test
    @DisplayName("GET /api/v1/admin/coins/history - Bütün tranzaksiyaları qaytarır")
    void testGetAllTransactions() {
        CoinTransactionResponse tx = CoinTransactionResponse.builder()
                .id(1L)
                .type(CoinTransactionType.BONUS)
                .amount(new BigDecimal("50.00"))
                .createdDate(LocalDateTime.now())
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        when(coinWalletService.getAllTransactionsForAdmin(any()))
                .thenReturn(new PageImpl<>(List.of(tx), pageable, 1));

        ResponseEntity<PaginatedResponse<CoinTransactionResponse>> response = coinAdminController.getAllTransactions(pageable);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().items().size());
    }
}
