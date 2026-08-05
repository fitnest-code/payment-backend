package az.fitnest.payment.service;

import az.fitnest.payment.dto.coin.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;

public interface CoinWalletService {

    CoinWalletResponse getWalletInfo(Long userId);

    Page<CoinTransactionResponse> getTransactionHistory(Long userId, Pageable pageable);

    CalculateDiscountResponse calculateCheckoutDiscount(Long userId, BigDecimal originalPrice, BigDecimal coinsToUse);

    CoinWalletResponse awardWelcomeBonus(Long userId, WelcomeBonusRequest request);

    void processPaymentCoins(Long userId, String orderId, Long paymentId, BigDecimal coinsUsed, BigDecimal netPaidAmount);

    void processRefundCoins(Long userId, String orderId, Long paymentId, BigDecimal coinsOriginallySpent, BigDecimal coinsOriginallyEarned);

    void expireOutdatedCoins();

    // Admin methods
    CoinSettingsResponse getSettings();

    CoinSettingsResponse updateSettings(CoinSettingsRequest request);

    CoinWalletResponse manualAdjustCoins(ManualCoinAdjustRequest request);

    Page<CoinTransactionResponse> getAllTransactionsForAdmin(Pageable pageable);
}
