package az.fitnest.payment.service;

import az.fitnest.payment.dto.coin.CalculateDiscountResponse;
import az.fitnest.payment.dto.coin.CoinWalletResponse;
import az.fitnest.payment.dto.coin.WelcomeBonusRequest;
import az.fitnest.payment.exception.ConflictException;
import az.fitnest.payment.model.entity.CoinSettings;
import az.fitnest.payment.model.entity.CoinTransaction;
import az.fitnest.payment.model.entity.CoinWallet;
import az.fitnest.payment.model.enums.CoinTransactionType;
import az.fitnest.payment.repository.CoinSettingsRepository;
import az.fitnest.payment.repository.CoinTransactionRepository;
import az.fitnest.payment.repository.CoinWalletRepository;
import az.fitnest.payment.repository.WelcomeBonusIdentifierRepository;
import az.fitnest.payment.service.impl.CoinWalletServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CoinWalletServiceTest {

    @Mock
    private CoinWalletRepository walletRepository;

    @Mock
    private CoinTransactionRepository transactionRepository;

    @Mock
    private CoinSettingsRepository settingsRepository;

    @Mock
    private WelcomeBonusIdentifierRepository welcomeBonusIdentifierRepository;

    @InjectMocks
    private CoinWalletServiceImpl coinWalletService;

    private CoinSettings defaultSettings;

    @BeforeEach
    void setUp() {
        defaultSettings = new CoinSettings();
        defaultSettings.setWelcomeBonusAmount(new BigDecimal("50.00"));
        defaultSettings.setEarnRateAznToCoin(new BigDecimal("1.00"));
        defaultSettings.setSpendRateCoinToAzn(new BigDecimal("20.00"));
        defaultSettings.setMaxDiscountPercentage(new BigDecimal("20.00"));
        defaultSettings.setExpiryMonths(12);
        defaultSettings.setActive(true);
    }

    @Test
    @DisplayName("Welcome bonus verilməsi - Uğurlu ssenari")
    void testAwardWelcomeBonus_Success() {
        Long userId = 100L;
        WelcomeBonusRequest request = new WelcomeBonusRequest("+994501234567", "test@fitnest.az");

        when(settingsRepository.findFirstByActiveTrueOrderByIdDesc()).thenReturn(Optional.of(defaultSettings));
        when(welcomeBonusIdentifierRepository.existsByUserId(userId)).thenReturn(false);

        CoinWallet wallet = new CoinWallet(userId, BigDecimal.ZERO);
        when(walletRepository.findByUserIdWithLock(userId)).thenReturn(Optional.of(wallet));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findExpiringSoonAmount(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.findNextExpiryDate(any(), any())).thenReturn(Optional.empty());

        CoinWalletResponse response = coinWalletService.awardWelcomeBonus(userId, request);

        assertNotNull(response);
        assertEquals(new BigDecimal("50.00"), response.getTotalBalance());
        verify(walletRepository).save(wallet);
        verify(transactionRepository).save(any(CoinTransaction.class));
        verify(welcomeBonusIdentifierRepository).save(any());
    }

    @Test
    @DisplayName("Welcome bonus təkrar verilə bilməz - ConflictException")
    void testAwardWelcomeBonus_AlreadyGiven_ThrowsConflict() {
        Long userId = 100L;
        WelcomeBonusRequest request = new WelcomeBonusRequest("+994501234567", "test@fitnest.az");

        when(settingsRepository.findFirstByActiveTrueOrderByIdDesc()).thenReturn(Optional.of(defaultSettings));
        when(welcomeBonusIdentifierRepository.existsByUserId(userId)).thenReturn(true);

        assertThrows(ConflictException.class, () -> coinWalletService.awardWelcomeBonus(userId, request));
    }

    @Test
    @DisplayName("Checkout endirim hesablama - 20% limit aşıldıqda limitin tətbiq edilməsi")
    void testCalculateCheckoutDiscount_MaxDiscountLimit() {
        Long userId = 100L;
        BigDecimal originalPrice = new BigDecimal("10.00"); // 20% max discount = 2.00 AZN
        BigDecimal coinsToUse = new BigDecimal("100.00"); // 100 coins = 5.00 AZN (exceeds 2.00 AZN)

        when(settingsRepository.findFirstByActiveTrueOrderByIdDesc()).thenReturn(Optional.of(defaultSettings));
        CoinWallet wallet = new CoinWallet(userId, new BigDecimal("100.00"));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        CalculateDiscountResponse response = coinWalletService.calculateCheckoutDiscount(userId, originalPrice, coinsToUse);

        assertEquals(originalPrice, response.getOriginalPrice());
        assertEquals(new BigDecimal("2.00"), response.getAppliedDiscountAzn());
        assertEquals(new BigDecimal("40.00"), response.getCoinsToUse()); // 2.00 AZN * 20 = 40 Coins
        assertEquals(new BigDecimal("8.00"), response.getFinalPaymentAmount());
        assertTrue(response.getIsMaxDiscountReached());
    }

    @Test
    @DisplayName("Checkout endirim hesablama - Limiti aşmadıqda tam hesablanması")
    void testCalculateCheckoutDiscount_BelowMaxDiscountLimit() {
        Long userId = 100L;
        BigDecimal originalPrice = new BigDecimal("50.00"); // 20% max discount = 10.00 AZN
        BigDecimal coinsToUse = new BigDecimal("20.00"); // 20 coins = 1.00 AZN

        when(settingsRepository.findFirstByActiveTrueOrderByIdDesc()).thenReturn(Optional.of(defaultSettings));
        CoinWallet wallet = new CoinWallet(userId, new BigDecimal("50.00"));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        CalculateDiscountResponse response = coinWalletService.calculateCheckoutDiscount(userId, originalPrice, coinsToUse);

        assertEquals(new BigDecimal("1.00"), response.getAppliedDiscountAzn());
        assertEquals(new BigDecimal("20.00"), response.getCoinsToUse());
        assertEquals(new BigDecimal("49.00"), response.getFinalPaymentAmount());
        assertFalse(response.getIsMaxDiscountReached());
    }

    @Test
    @DisplayName("Ödəniş emalı - Earliest Expiration First prinsipi ilə coin xərcləmə və net sumadan coin qazanma")
    void testProcessPaymentCoins_SpendAndEarn() {
        Long userId = 100L;
        CoinWallet wallet = new CoinWallet(userId, new BigDecimal("50.00"));
        when(settingsRepository.findFirstByActiveTrueOrderByIdDesc()).thenReturn(Optional.of(defaultSettings));
        when(walletRepository.findByUserIdWithLock(userId)).thenReturn(Optional.of(wallet));

        CoinTransaction batch1 = new CoinTransaction();
        batch1.setRemainingAmount(new BigDecimal("20.00"));
        batch1.setExpiryDate(LocalDateTime.now().plusMonths(1));

        CoinTransaction batch2 = new CoinTransaction();
        batch2.setRemainingAmount(new BigDecimal("30.00"));
        batch2.setExpiryDate(LocalDateTime.now().plusMonths(6));

        when(transactionRepository.findActiveEarnBatchesForSpending(eq(userId), any())).thenReturn(List.of(batch1, batch2));

        // Spending 25 coins, net paid 80 AZN
        coinWalletService.processPaymentCoins(userId, "ORD-123", 1L, new BigDecimal("25.00"), new BigDecimal("80.00"));

        assertEquals(new BigDecimal("0.00"), batch1.getRemainingAmount()); // First batch of 20 completely used
        assertEquals(new BigDecimal("25.00"), batch2.getRemainingAmount()); // Second batch reduced by 5 (30-5=25)

        ArgumentCaptor<CoinWallet> walletCaptor = ArgumentCaptor.forClass(CoinWallet.class);
        verify(walletRepository).save(walletCaptor.capture());

        // Final balance = initial(50) - spent(25) + earned(80) = 105.00
        assertEquals(new BigDecimal("105.00"), walletCaptor.getValue().getBalance());
    }

    @Test
    @DisplayName("Refund emalı - Balans mənfiyə düşə bilməz")
    void testProcessRefundCoins_NoNegativeBalance() {
        Long userId = 100L;
        CoinWallet wallet = new CoinWallet(userId, new BigDecimal("10.00")); // User only has 10 coins left
        when(walletRepository.findByUserIdWithLock(userId)).thenReturn(Optional.of(wallet));

        // Refund order where 50 coins were originally earned
        coinWalletService.processRefundCoins(userId, "ORD-123", 1L, BigDecimal.ZERO, new BigDecimal("50.00"));

        // Only 10 coins can be revoked, balance drops to 0.00 (not negative!)
        assertEquals(new BigDecimal("0.00"), wallet.getBalance());
        verify(transactionRepository).save(any(CoinTransaction.class));
    }
}
