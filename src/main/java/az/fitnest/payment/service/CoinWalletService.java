package az.fitnest.payment.service;

import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.model.enums.CoinTransactionCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;

public interface CoinWalletService {

    CoinWalletResponse getWalletInfo(Long userId);

    Page<CoinTransactionResponse> getTransactionHistory(Long userId, CoinTransactionCategory category, Pageable pageable);

    CalculateDiscountResponse calculateCheckoutDiscount(Long userId, CalculateDiscountRequest request);

    PayFullWithCoinsResponse payFullWithCoins(Long userId, PayFullWithCoinsRequest request);

    CoinWalletResponse awardWelcomeBonus(Long userId, WelcomeBonusRequest request);

    void processPaymentCoins(Long userId, String orderId, Long paymentId, BigDecimal coinsUsed, BigDecimal netPaidAmount);

    void processRefundCoins(Long userId, String orderId, Long paymentId, BigDecimal coinsOriginallySpent, BigDecimal coinsOriginallyEarned);

    void expireOutdatedCoins();

    // Admin methods
    CoinSettingsResponse getSettings();

    CoinSettingsResponse updateSettings(CoinSettingsRequest request);

    CoinWalletResponse manualAdjustCoins(ManualCoinAdjustRequest request);

    BulkCoinAdjustResponse bulkAdjustCoins(BulkCoinAdjustRequest request);

    Page<CoinTransactionResponse> getAllTransactionsForAdmin(Pageable pageable);
}
