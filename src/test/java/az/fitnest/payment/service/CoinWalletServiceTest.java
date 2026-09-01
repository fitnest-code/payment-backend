package az.fitnest.payment.service;

import az.fitnest.payment.client.IdentityBackendClient;
import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.UserGrpcClient;
import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.exception.ConflictException;
import az.fitnest.payment.model.entity.CoinSettings;
import az.fitnest.payment.model.entity.CoinTransaction;
import az.fitnest.payment.model.entity.CoinWallet;
import az.fitnest.payment.model.enums.CoinRefundAction;
import az.fitnest.payment.model.enums.CoinTransactionCategory;
import az.fitnest.payment.model.enums.CoinTransactionSourceType;
import az.fitnest.payment.model.enums.CoinTransactionType;
import az.fitnest.payment.repository.CoinSettingsRepository;
import az.fitnest.payment.repository.CoinTransactionRepository;
import az.fitnest.payment.repository.CoinWalletRepository;
import az.fitnest.payment.repository.WelcomeBonusIdentifierRepository;
import az.fitnest.payment.service.impl.CoinWalletServiceImpl;
import az.fitnest.payment.service.coin.CoinNotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Mock
    private SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    @Mock
    private IdentityBackendClient identityBackendClient;

    @Mock
    private UserGrpcClient userGrpcClient;

    @Mock
    private CoinNotificationPublisher coinNotificationPublisher;

    @InjectMocks
    private CoinWalletServiceImpl coinWalletService;

    private CoinSettings defaultSettings;

    @BeforeEach
    void setUp() {
        defaultSettings = new CoinSettings();
        defaultSettings.setWelcomeBonusAmount(new BigDecimal("50.00"));
        defaultSettings.setEarnRateAznToCoin(new BigDecimal("1.00"));
        defaultSettings.setSpendRateCoinToAzn(new BigDecimal("20.00"));
        defaultSettings.setMaxDiscountPercentage(new BigDecimal("100.00"));
        defaultSettings.setExpiryMonths(12);
        defaultSettings.setActive(true);
    }

    @Test
    @DisplayName("Welcome bonus verilməsi - Wallet-level Expiry set olunması (365 gün)")
    void testAwardWelcomeBonus_Success() {
        Long userId = 100L;
        WelcomeBonusRequest request = WelcomeBonusRequest.builder()
                .phone("+994501234567")
                .email("test@fitnest.az")
                .build();

        when(settingsRepository.findFirstByActiveTrueOrderByIdDesc()).thenReturn(Optional.of(defaultSettings));
        when(identityBackendClient.isWelcomeBonusReceived(userId)).thenReturn(false);
        when(welcomeBonusIdentifierRepository.existsByUserId(userId)).thenReturn(false);

        CoinWallet wallet = new CoinWallet(userId, BigDecimal.ZERO);
        when(walletRepository.findByUserIdWithLock(userId)).thenReturn(Optional.of(wallet));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        CoinWalletResponse response = coinWalletService.awardWelcomeBonus(userId, request);

        assertNotNull(response);
        assertEquals(new BigDecimal("50.00"), response.getTotalBalance());
        assertNotNull(response.getFirstCoinEarnedAt());
        assertNotNull(response.getExpiryDate());
        verify(walletRepository).save(wallet);
        verify(transactionRepository).save(any(CoinTransaction.class));
        verify(welcomeBonusIdentifierRepository).save(any());
        verify(identityBackendClient).markWelcomeBonusReceived(userId);
    }

    @Test
    @DisplayName("Checkout endirim hesablama - Dynamic Price Resolution (Təhlükəsizlik testi)")
    void testCalculateCheckoutDiscount_DynamicPriceResolution() {
        Long userId = 100L;
        CalculateDiscountRequest request = CalculateDiscountRequest.builder()
                .subscriptionPlanId(123L)
                .useCoin(true)
                .build();

        when(settingsRepository.findFirstByActiveTrueOrderByIdDesc()).thenReturn(Optional.of(defaultSettings));
        CoinWallet wallet = new CoinWallet(userId, new BigDecimal("320.00")); // 320 coins = 16.00 AZN
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        // Mock gRPC client returning official package price 50.00 AZN
        when(subscriptionPackageGrpcClient.getOptionPriceCurrency(eq(123L), any()))
                .thenReturn(new SubscriptionPackageGrpcClient.OptionPriceCurrency(50.0, "AZN", 1));

        CalculateDiscountResponse response = coinWalletService.calculateCheckoutDiscount(userId, request);

        assertEquals(new BigDecimal("50.00"), response.getOriginalPrice());
        assertEquals(new BigDecimal("320.00"), response.getCoin().getAvailableBalance());
        assertEquals(new BigDecimal("16.00"), response.getCoin().getAvailableAzn());
        assertEquals(new BigDecimal("320.00"), response.getCoin().getAppliedCoins());
        assertEquals(new BigDecimal("16.00"), response.getCoin().getDiscountAzn());
        assertEquals(new BigDecimal("34.00"), response.getFinalPaymentAmount());
        assertFalse(response.getIsFullCoinPaymentAvailable());
    }

    @Test
    @DisplayName("100% Coin ilə ödəniş (payFullWithCoins) - Dynamic Price Resolution və Coin çıxılması")
    void testPayFullWithCoins_Success() {
        Long userId = 100L;
        PayFullWithCoinsRequest request = PayFullWithCoinsRequest.builder()
                .subscriptionPlanId(123L)
                .build();

        when(settingsRepository.findFirstByActiveTrueOrderByIdDesc()).thenReturn(Optional.of(defaultSettings));
        CoinWallet wallet = new CoinWallet(userId, new BigDecimal("300.00"));
        when(walletRepository.findByUserIdWithLock(userId)).thenReturn(Optional.of(wallet));

        when(subscriptionPackageGrpcClient.getOptionPriceCurrency(eq(123L), any()))
                .thenReturn(new SubscriptionPackageGrpcClient.OptionPriceCurrency(10.0, "AZN", 1)); // 10 AZN = 200 coins

        PayFullWithCoinsResponse response = coinWalletService.payFullWithCoins(userId, request);

        assertTrue(response.getSuccess());
        assertNotNull(response.getOrderId());
        assertEquals(new BigDecimal("200.00"), response.getCoinsDeducted());
        assertEquals(new BigDecimal("100.00"), response.getRemainingBalance());
        verify(transactionRepository).save(any(CoinTransaction.class));
    }

    @Test
    @DisplayName("Ödəniş emalı - Coin ilə ödənilən hissə üzrə təkrar Coin hesablanmır (netPaidAmount = 0 -> earned = 0)")
    void testProcessPaymentCoins_NoEarnOnFullCoinPayment() {
        Long userId = 100L;
        CoinWallet wallet = new CoinWallet(userId, new BigDecimal("200.00"));
        when(settingsRepository.findFirstByActiveTrueOrderByIdDesc()).thenReturn(Optional.of(defaultSettings));
        when(walletRepository.findByUserIdWithLock(userId)).thenReturn(Optional.of(wallet));

        // Spending 200 coins, net card paid 0.00 AZN
        coinWalletService.processPaymentCoins(userId, "COIN-ORD-123", 1L, new BigDecimal("200.00"), BigDecimal.ZERO);

        // BigDecimal scale-agnostic comparison (0 == 0.00)
        assertEquals(0, wallet.getBalance().compareTo(BigDecimal.ZERO), "Balans sıfır olmalıdır");
        // Verify only 1 SPEND transaction created, NO EARN transaction created!
        verify(transactionRepository, times(1)).save(any(CoinTransaction.class));
    }

    // ─── History Category Filter Tests ───────────────────────────────────────────

    @Test
    @DisplayName("History - EARN tranzaksiyası sourceType=SUBSCRIPTION_PURCHASE, sourceTitle=description qaytarır")
    void testHistoryMapping_EarnTransaction_HasCorrectSourceType() {
        Long userId = 50L;
        CoinTransaction tx = new CoinTransaction();
        tx.setType(CoinTransactionType.EARN);
        tx.setAmount(new BigDecimal("52.50"));
        tx.setBalanceAfter(new BigDecimal("152.50"));
        tx.setDescription("Bronze - 1 aylıq abunəlik");
        tx.setOrderId("ORD-123");

        var pageResult = new org.springframework.data.domain.PageImpl<>(List.of(tx));
        when(transactionRepository.findByUserIdOrderByCreatedDateDesc(eq(userId), any()))
                .thenReturn(pageResult);

        var result = coinWalletService.getTransactionHistory(userId, CoinTransactionCategory.ALL,
                org.springframework.data.domain.PageRequest.of(0, 20));

        assertEquals(1, result.getTotalElements());
        CoinTransactionResponse resp = result.getContent().get(0);
        assertEquals(CoinTransactionSourceType.SUBSCRIPTION_PURCHASE, resp.getSourceType());
        assertEquals("Bronze - 1 aylıq abunəlik", resp.getSourceTitle());
    }

    @Test
    @DisplayName("History - BONUS tranzaksiyası sourceType=WELCOME_BONUS qaytarır, description yoxdursa default title")
    void testHistoryMapping_BonusTransaction_WelcomeBonusSourceType() {
        Long userId = 50L;
        CoinTransaction tx = new CoinTransaction();
        tx.setType(CoinTransactionType.BONUS);
        tx.setAmount(new BigDecimal("50.00"));
        tx.setBalanceAfter(new BigDecimal("50.00"));
        // description yoxdur — default title gəlməlidir

        var pageResult = new org.springframework.data.domain.PageImpl<>(List.of(tx));
        when(transactionRepository.findByUserIdOrderByCreatedDateDesc(eq(userId), any()))
                .thenReturn(pageResult);

        var result = coinWalletService.getTransactionHistory(userId, CoinTransactionCategory.ALL,
                org.springframework.data.domain.PageRequest.of(0, 20));

        CoinTransactionResponse resp = result.getContent().get(0);
        assertEquals(CoinTransactionSourceType.WELCOME_BONUS, resp.getSourceType());
        assertEquals("Qeydiyyat bonusu", resp.getSourceTitle());
    }

    @Test
    @DisplayName("processRefundCoins - RESTORE_SPENT_COINS və REVERSE_EARNED_COINS tranzaksiyaları yaradılır")
    void testProcessRefundCoins_CreatesSpentAndEarnedRefundActions() {
        Long userId = 77L;
        CoinWallet wallet = new CoinWallet(userId, new BigDecimal("100.00"));
        when(walletRepository.findByUserIdWithLock(userId)).thenReturn(Optional.of(wallet));

        // Spending 120 coins restored, 52.50 coins earned revoked
        coinWalletService.processRefundCoins(userId, "ORD-REFUND-1", 10L, new BigDecimal("120.00"), new BigDecimal("52.50"));

        org.mockito.ArgumentCaptor<CoinTransaction> captor = org.mockito.ArgumentCaptor.forClass(CoinTransaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());

        List<CoinTransaction> savedTxs = captor.getAllValues();
        assertEquals(CoinRefundAction.RESTORE_SPENT_COINS, savedTxs.get(0).getRefundAction());
        assertEquals(new BigDecimal("120.00"), savedTxs.get(0).getAmount());

        assertEquals(CoinRefundAction.REVERSE_EARNED_COINS, savedTxs.get(1).getRefundAction());
        assertEquals(new BigDecimal("-52.50"), savedTxs.get(1).getAmount());
    }

    @Test
    @DisplayName("calculateCheckoutDiscount - Plan və Coin məlumatlarını strukturlaşdırılmış şəkildə qaytarır")
    void testCalculateCheckoutDiscount_ReturnsStructuredPlanAndCoinInfo() {
        Long userId = 100L;
        CoinWallet wallet = new CoinWallet(userId, new BigDecimal("320.00"));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(settingsRepository.findFirstByActiveTrueOrderByIdDesc()).thenReturn(Optional.of(defaultSettings));
        when(subscriptionPackageGrpcClient.getOptionPriceCurrency(123L, 1L))
                .thenReturn(new SubscriptionPackageGrpcClient.OptionPriceCurrency(50.00, "AZN", 1));

        CalculateDiscountRequest request = CalculateDiscountRequest.builder()
                .subscriptionPlanId(123L)
                .optionId(1L)
                .useCoin(true)
                .build();

        CalculateDiscountResponse response = coinWalletService.calculateCheckoutDiscount(userId, request);

        assertNotNull(response.getPlan());
        assertEquals(123L, response.getPlan().getId());
        assertEquals("1 ay", response.getPlan().getDuration());

        assertNotNull(response.getCoin());
        assertEquals(new BigDecimal("320.00"), response.getCoin().getAvailableBalance());
        assertEquals(new BigDecimal("16.00"), response.getCoin().getAvailableAzn());
        assertEquals(new BigDecimal("320.00"), response.getCoin().getAppliedCoins());
        assertEquals(new BigDecimal("16.00"), response.getCoin().getDiscountAzn());

        assertNotNull(response.getDiscounts());
        assertEquals(1, response.getDiscounts().size());
        assertEquals("COIN", response.getDiscounts().get(0).getType());
        assertEquals(new BigDecimal("16.00"), response.getDiscounts().get(0).getAmount());
        assertEquals(new BigDecimal("16.00"), response.getTotalDiscountAmount());

        assertEquals(0, new BigDecimal("34.00").compareTo(response.getFinalPaymentAmount()));
    }
}
