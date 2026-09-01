package az.fitnest.payment.service.impl;

import az.fitnest.payment.client.IdentityBackendClient;
import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.UserGrpcClient;
import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.exception.BadRequestException;
import az.fitnest.payment.exception.ConflictException;
import az.fitnest.payment.model.entity.CoinSettings;
import az.fitnest.payment.model.entity.CoinTransaction;
import az.fitnest.payment.model.entity.CoinWallet;
import az.fitnest.payment.model.entity.WelcomeBonusIdentifier;
import az.fitnest.payment.model.enums.CoinRefundAction;
import az.fitnest.payment.model.enums.CoinTransactionCategory;
import az.fitnest.payment.model.enums.CoinTransactionSourceType;
import az.fitnest.payment.model.enums.CoinTransactionType;
import az.fitnest.payment.repository.CoinSettingsRepository;
import az.fitnest.payment.repository.CoinTransactionRepository;
import az.fitnest.payment.repository.CoinWalletRepository;
import az.fitnest.payment.repository.WelcomeBonusIdentifierRepository;
import az.fitnest.payment.service.CoinWalletService;
import az.fitnest.payment.service.coin.CoinNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoinWalletServiceImpl implements CoinWalletService {

    private final CoinWalletRepository walletRepository;
    private final CoinTransactionRepository transactionRepository;
    private final CoinSettingsRepository settingsRepository;
    private final WelcomeBonusIdentifierRepository welcomeBonusIdentifierRepository;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final IdentityBackendClient identityBackendClient;
    private final UserGrpcClient userGrpcClient;
    private final CoinNotificationPublisher coinNotificationPublisher;

    @Override
    @Transactional(readOnly = true)
    public CoinWalletResponse getWalletInfo(Long userId) {
        CoinWallet wallet = getOrCreateWallet(userId);
        CoinSettings settings = getSettingsInternal();

        BigDecimal balance = wallet.getBalance();
        BigDecimal aznEquivalent = balance.divide(settings.getSpendRateCoinToAzn(), 2, RoundingMode.HALF_UP);

        LocalDateTime now = LocalDateTime.now();
        Long daysUntilExpiry = null;
        if (wallet.getExpiryDate() != null) {
            long days = Duration.between(now, wallet.getExpiryDate()).toDays();
            daysUntilExpiry = days > 0 ? days : 0L;
        }

        return CoinWalletResponse.builder()
                .totalBalance(balance)
                .aznEquivalent(aznEquivalent)
                .firstCoinEarnedAt(wallet.getFirstCoinEarnedAt())
                .expiryDate(wallet.getExpiryDate())
                .daysUntilExpiry(daysUntilExpiry)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CoinTransactionResponse> getTransactionHistory(Long userId, CoinTransactionCategory category, Pageable pageable) {
        if (category == null || category == CoinTransactionCategory.ALL) {
            return transactionRepository.findByUserIdOrderByCreatedDateDesc(userId, pageable)
                    .map(this::mapToTransactionResponse);
        }

        return switch (category) {
            case EARNED -> transactionRepository.findEarnedTransactionsByUserId(
                    userId,
                    List.of(CoinTransactionType.BONUS, CoinTransactionType.EARN, CoinTransactionType.CAMPAIGN_BONUS),
                    pageable
            ).map(this::mapToTransactionResponse);
            case SPENT -> transactionRepository.findSpentTransactionsByUserId(
                    userId,
                    pageable
            ).map(this::mapToTransactionResponse);
            case EXPIRED -> transactionRepository.findByUserIdAndTypeInOrderByCreatedDateDesc(
                    userId,
                    List.of(CoinTransactionType.EXPIRE),
                    pageable
            ).map(this::mapToTransactionResponse);
            default -> transactionRepository.findByUserIdOrderByCreatedDateDesc(userId, pageable)
                    .map(this::mapToTransactionResponse);
        };
    }

    @Override
    @Transactional(readOnly = true)
    public CalculateDiscountResponse calculateCheckoutDiscount(Long userId, CalculateDiscountRequest request) {
        CoinSettings settings = getSettingsInternal();
        CoinWallet wallet = getOrCreateWallet(userId);

        Long planId = request.getSubscriptionPlanId();
        Long optionId = request.getOptionId();

        BigDecimal originalPrice = resolvePackagePrice(planId, optionId, request.getOriginalPrice());
        BigDecimal availableCoinBalance = wallet.getBalance();
        BigDecimal availableCoinAzn = availableCoinBalance.divide(settings.getSpendRateCoinToAzn(), 2, RoundingMode.HALF_UP);

        boolean useCoin = Boolean.TRUE.equals(request.getUseCoin()) ||
                (request.getCoinsToUse() != null && request.getCoinsToUse().compareTo(BigDecimal.ZERO) > 0);

        BigDecimal appliedCoins = BigDecimal.ZERO;
        BigDecimal appliedDiscountAzn = BigDecimal.ZERO;
        BigDecimal finalPaymentAmount = originalPrice;

        if (useCoin && availableCoinBalance.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal maxCoinsNeeded = originalPrice.multiply(settings.getSpendRateCoinToAzn()).setScale(2, RoundingMode.HALF_UP);
            appliedCoins = availableCoinBalance.min(maxCoinsNeeded);
            appliedDiscountAzn = appliedCoins.divide(settings.getSpendRateCoinToAzn(), 2, RoundingMode.HALF_UP);
            if (appliedDiscountAzn.compareTo(originalPrice) > 0) {
                appliedDiscountAzn = originalPrice;
            }
            finalPaymentAmount = originalPrice.subtract(appliedDiscountAzn);
            if (finalPaymentAmount.compareTo(BigDecimal.ZERO) < 0) {
                finalPaymentAmount = BigDecimal.ZERO;
            }
        }

        BigDecimal fullCoinsRequired = originalPrice.multiply(settings.getSpendRateCoinToAzn()).setScale(2, RoundingMode.HALF_UP);
        boolean isFullCoinPaymentAvailable = (availableCoinBalance.compareTo(fullCoinsRequired) >= 0);

        // Fetch Plan Name & Option Duration for frontend semantics
        String planName = "Abunəlik Paketi";
        String durationStr = "1 ay";
        if (planId != null) {
            try {
                var pkgList = subscriptionPackageGrpcClient.getPackageNamesByIds(List.of(planId));
                if (!pkgList.isEmpty() && pkgList.get(0).getName() != null) {
                    planName = pkgList.get(0).getName();
                }
                if (optionId != null) {
                    var optionDetails = subscriptionPackageGrpcClient.getOptionPriceCurrency(planId, optionId);
                    if (optionDetails != null && optionDetails.durationMonths > 0) {
                        durationStr = optionDetails.durationMonths + " ay";
                    }
                }
            } catch (Exception e) {
                log.warn("Could not fetch package/option details via gRPC for planId: {}, optionId: {}", planId, optionId);
            }
        }

        List<CheckoutDiscountItem> discounts = new ArrayList<>();
        if (appliedDiscountAzn.compareTo(BigDecimal.ZERO) > 0) {
            discounts.add(CheckoutDiscountItem.builder()
                    .type("COIN")
                    .amount(appliedDiscountAzn)
                    .coinsUsed(appliedCoins)
                    .description("FitNest Coin endirimi")
                    .build());
        }

        BigDecimal totalDiscountAmount = appliedDiscountAzn;

        CheckoutPlanInfo planInfo = CheckoutPlanInfo.builder()
                .id(planId)
                .name(planName)
                .duration(durationStr)
                .build();

        CheckoutCoinInfo coinInfo = CheckoutCoinInfo.builder()
                .availableBalance(availableCoinBalance)
                .availableAzn(availableCoinAzn)
                .appliedCoins(appliedCoins)
                .discountAzn(appliedDiscountAzn)
                .build();

        return CalculateDiscountResponse.builder()
                .plan(planInfo)
                .originalPrice(originalPrice)
                .discounts(discounts)
                .totalDiscountAmount(totalDiscountAmount)
                .finalPaymentAmount(finalPaymentAmount)
                .coin(coinInfo)
                .isFullCoinPaymentAvailable(isFullCoinPaymentAvailable)
                .build();
    }

    @Override
    @Transactional
    public PayFullWithCoinsResponse payFullWithCoins(Long userId, PayFullWithCoinsRequest request) {
        CoinSettings settings = getSettingsInternal();
        CoinWallet wallet = getOrCreateWalletWithLock(userId);

        BigDecimal originalPrice = resolvePackagePrice(request.getSubscriptionPlanId(), request.getOptionId(), request.getOriginalPrice());
        BigDecimal coinsNeeded = originalPrice.multiply(settings.getSpendRateCoinToAzn()).setScale(2, RoundingMode.HALF_UP);

        if (wallet.getBalance().compareTo(coinsNeeded) < 0) {
            throw new BadRequestException("100% Coin ilə ödəniş üçün balansınızda kifayət qədər Coin yoxdur");
        }

        BigDecimal newBalance = wallet.getBalance().subtract(coinsNeeded);
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        String orderId = "COIN-ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        CoinTransaction spendTx = new CoinTransaction();
        spendTx.setWallet(wallet);
        spendTx.setUserId(userId);
        spendTx.setType(CoinTransactionType.SPEND);
        spendTx.setAmount(coinsNeeded.negate());
        spendTx.setBalanceAfter(newBalance);
        spendTx.setOrderId(orderId);
        spendTx.setRemainingAmount(BigDecimal.ZERO);
        spendTx.setDescription("100% Coin ilə paket ödənişi edildi");
        transactionRepository.save(spendTx);

        log.info("Full coin payment executed for userId: {}, planId: {}, coinsDeducted: {}, orderId: {}",
                userId, request.getSubscriptionPlanId(), coinsNeeded, orderId);

        return PayFullWithCoinsResponse.builder()
                .success(true)
                .orderId(orderId)
                .subscriptionPlanId(request.getSubscriptionPlanId())
                .coinsDeducted(coinsNeeded)
                .remainingBalance(newBalance)
                .message("Ödəniş tam olaraq Coin ilə həyata keçirildi və abunəlik aktivləşdirildi")
                .build();
    }

    @Override
    @Transactional
    public CoinWalletResponse awardWelcomeBonus(Long userId, WelcomeBonusRequest request) {
        CoinSettings settings = getSettingsInternal();
        if (!Boolean.TRUE.equals(settings.getActive())) {
            log.info("Welcome bonus skipped for userId={}: coin program is inactive", userId);
            return getWalletInfo(userId);
        }

        BigDecimal configuredBonus = settings.getWelcomeBonusAmount();
        if (configuredBonus == null || configuredBonus.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Welcome bonus skipped for userId={}: welcomeBonusAmount not configured in admin settings", userId);
            return getWalletInfo(userId);
        }

        if (identityBackendClient.isWelcomeBonusReceived(userId)) {
            log.info("Welcome bonus already received for userId={} (identity flag)", userId);
            return getWalletInfo(userId);
        }

        if (welcomeBonusIdentifierRepository.existsByUserId(userId)) {
            identityBackendClient.markWelcomeBonusReceived(userId);
            log.info("Synced welcome bonus received flag for userId={} from existing identifier", userId);
            return getWalletInfo(userId);
        }

        String phoneHash = (request != null && request.getPhone() != null && !request.getPhone().isBlank())
                ? hashString(request.getPhone().trim()) : null;
        String emailHash = (request != null && request.getEmail() != null && !request.getEmail().isBlank())
                ? hashString(request.getEmail().trim().toLowerCase()) : null;

        if (phoneHash != null && welcomeBonusIdentifierRepository.existsByPhoneHash(phoneHash)) {
            throw new ConflictException("Bu telefon nömrəsinə Welcome bonus artıq verilib");
        }
        if (emailHash != null && welcomeBonusIdentifierRepository.existsByEmailHash(emailHash)) {
            throw new ConflictException("Bu e-poçt ünvanına Welcome bonus artıq verilib");
        }

        CoinWallet wallet = getOrCreateWalletWithLock(userId);
        BigDecimal bonusAmount = configuredBonus;
        BigDecimal newBalance = wallet.getBalance().add(bonusAmount);
        wallet.setBalance(newBalance);

        LocalDateTime now = LocalDateTime.now();
        if (wallet.getFirstCoinEarnedAt() == null) {
            wallet.setFirstCoinEarnedAt(now);
            wallet.setExpiryDate(now.plusDays(365));
        }
        walletRepository.save(wallet);

        CoinTransaction transaction = new CoinTransaction();
        transaction.setWallet(wallet);
        transaction.setUserId(userId);
        transaction.setType(CoinTransactionType.BONUS);
        transaction.setAmount(bonusAmount);
        transaction.setBalanceAfter(newBalance);
        transaction.setRemainingAmount(bonusAmount);
        transaction.setExpiryDate(wallet.getExpiryDate());
        transaction.setDescription(null);
        transactionRepository.save(transaction);

        WelcomeBonusIdentifier identifier = new WelcomeBonusIdentifier();
        identifier.setUserId(userId);
        identifier.setPhoneHash(phoneHash);
        identifier.setEmailHash(emailHash);
        welcomeBonusIdentifierRepository.save(identifier);

        identityBackendClient.markWelcomeBonusReceived(userId);

        if (request != null && Boolean.TRUE.equals(request.getSendNotification())) {
            coinNotificationPublisher.sendPush(
                    userId,
                    request.getNotificationTitle(),
                    request.getNotificationBody());
        }

        log.info("Welcome bonus awarded to userId: {}, bonusAmount: {}", userId, bonusAmount);
        return getWalletInfo(userId);
    }

    @Override
    @Transactional
    public void processPaymentCoins(Long userId, String orderId, Long paymentId, BigDecimal coinsUsed, BigDecimal netPaidAmount) {
        CoinSettings settings = getSettingsInternal();
        CoinWallet wallet = getOrCreateWalletWithLock(userId);
        LocalDateTime now = LocalDateTime.now();

        if (wallet.getFirstCoinEarnedAt() == null && (
                (coinsUsed != null && coinsUsed.compareTo(BigDecimal.ZERO) > 0) ||
                (netPaidAmount != null && netPaidAmount.compareTo(BigDecimal.ZERO) > 0))) {
            wallet.setFirstCoinEarnedAt(now);
            wallet.setExpiryDate(now.plusDays(365));
        }

        // 1. Process Coins Spent
        if (coinsUsed != null && coinsUsed.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal newBalance = wallet.getBalance().subtract(coinsUsed);
            if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                newBalance = BigDecimal.ZERO;
            }
            wallet.setBalance(newBalance);

            CoinTransaction spendTx = new CoinTransaction();
            spendTx.setWallet(wallet);
            spendTx.setUserId(userId);
            spendTx.setType(CoinTransactionType.SPEND);
            spendTx.setAmount(coinsUsed.negate());
            spendTx.setBalanceAfter(newBalance);
            spendTx.setOrderId(orderId);
            spendTx.setPaymentId(paymentId);
            spendTx.setRemainingAmount(BigDecimal.ZERO);
            spendTx.setDescription("Checkout endirimi üçün xərcləndi");
            transactionRepository.save(spendTx);
        }

        // 2. Process Coins Earned (1 AZN net card payment = 1 Coin).
        // QEYD: Coin ilə alınan ödənişdən təkrar Coin hesablanmır (netPaidAmount kartdan ödənilən faktiki məbləğdir).
        if (netPaidAmount != null && netPaidAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal earnedCoins = netPaidAmount.multiply(settings.getEarnRateAznToCoin()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal newBalance = wallet.getBalance().add(earnedCoins);
            wallet.setBalance(newBalance);

            CoinTransaction earnTx = new CoinTransaction();
            earnTx.setWallet(wallet);
            earnTx.setUserId(userId);
            earnTx.setType(CoinTransactionType.EARN);
            earnTx.setAmount(earnedCoins);
            earnTx.setBalanceAfter(newBalance);
            earnTx.setRemainingAmount(earnedCoins);
            earnTx.setExpiryDate(wallet.getExpiryDate());
            earnTx.setOrderId(orderId);
            earnTx.setPaymentId(paymentId);
            earnTx.setDescription("Uğurlu ödəniş üzrə Coin qazanıldı");
            transactionRepository.save(earnTx);
        }

        walletRepository.save(wallet);
    }

    @Override
    @Transactional
    public void processRefundCoins(Long userId, String orderId, Long paymentId, BigDecimal coinsOriginallySpent, BigDecimal coinsOriginallyEarned) {
        CoinWallet wallet = getOrCreateWalletWithLock(userId);

        // 1. Return spent coins to user's wallet
        if (coinsOriginallySpent != null && coinsOriginallySpent.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal newBalance = wallet.getBalance().add(coinsOriginallySpent);
            wallet.setBalance(newBalance);

            CoinTransaction refundSpendTx = new CoinTransaction();
            refundSpendTx.setWallet(wallet);
            refundSpendTx.setUserId(userId);
            refundSpendTx.setType(CoinTransactionType.REFUND);
            refundSpendTx.setRefundAction(CoinRefundAction.RESTORE_SPENT_COINS);
            refundSpendTx.setAmount(coinsOriginallySpent);
            refundSpendTx.setBalanceAfter(newBalance);
            refundSpendTx.setRemainingAmount(coinsOriginallySpent);
            refundSpendTx.setExpiryDate(wallet.getExpiryDate());
            refundSpendTx.setOrderId(orderId);
            refundSpendTx.setPaymentId(paymentId);
            refundSpendTx.setDescription("Ləğv edilən ödəniş üzrə xərclənmiş Coin-lər geri qaytarıldı");
            transactionRepository.save(refundSpendTx);
        }

        // 2. Revoke earned coins (Ensure balance doesn't drop below 0 per BR-10)
        if (coinsOriginallyEarned != null && coinsOriginallyEarned.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal toRevoke = wallet.getBalance().min(coinsOriginallyEarned);
            BigDecimal newBalance = wallet.getBalance().subtract(toRevoke);
            wallet.setBalance(newBalance);

            CoinTransaction refundEarnTx = new CoinTransaction();
            refundEarnTx.setWallet(wallet);
            refundEarnTx.setUserId(userId);
            refundEarnTx.setType(CoinTransactionType.REFUND);
            refundEarnTx.setRefundAction(CoinRefundAction.REVERSE_EARNED_COINS);
            refundEarnTx.setAmount(toRevoke.negate());
            refundEarnTx.setBalanceAfter(newBalance);
            refundEarnTx.setRemainingAmount(BigDecimal.ZERO);
            refundEarnTx.setOrderId(orderId);
            refundEarnTx.setPaymentId(paymentId);
            refundEarnTx.setDescription("Ləğv edilən ödəniş üzrə qazanılmış Coin-lər geri alındı");
            transactionRepository.save(refundEarnTx);
        }

        walletRepository.save(wallet);
    }

    @Override
    @Transactional
    public void expireOutdatedCoins() {
        LocalDateTime now = LocalDateTime.now();
        List<CoinWallet> expiredWallets = walletRepository.findExpiredWallets(now);

        for (CoinWallet wallet : expiredWallets) {
            BigDecimal expiredAmount = wallet.getBalance();
            if (expiredAmount == null || expiredAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            wallet.setBalance(BigDecimal.ZERO);
            wallet.setFirstCoinEarnedAt(null);
            wallet.setExpiryDate(null);
            walletRepository.save(wallet);

            CoinTransaction expireTx = new CoinTransaction();
            expireTx.setWallet(wallet);
            expireTx.setUserId(wallet.getUserId());
            expireTx.setType(CoinTransactionType.EXPIRE);
            expireTx.setAmount(expiredAmount.negate());
            expireTx.setBalanceAfter(BigDecimal.ZERO);
            expireTx.setRemainingAmount(BigDecimal.ZERO);
            expireTx.setDescription("365 gün tamam olduğu üçün bütün Coin balansı silindi");
            transactionRepository.save(expireTx);

            log.info("Expired entire wallet balance of {} coins for userId: {}", expiredAmount, wallet.getUserId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CoinSettingsResponse getSettings() {
        return mapToSettingsResponse(getSettingsInternal());
    }

    @Override
    @Transactional
    public CoinSettingsResponse updateSettings(CoinSettingsRequest request) {
        CoinSettings settings = getSettingsInternal();
        settings.setWelcomeBonusAmount(request.getWelcomeBonusAmount());
        settings.setEarnRateAznToCoin(request.getEarnRateAznToCoin());
        settings.setSpendRateCoinToAzn(request.getSpendRateCoinToAzn());
        settings.setMaxDiscountPercentage(request.getMaxDiscountPercentage());
        settings.setExpiryMonths(request.getExpiryMonths());
        settings.setActive(request.getActive());

        CoinSettings saved = settingsRepository.save(settings);
        return mapToSettingsResponse(saved);
    }

    @Override
    @Transactional
    public CoinWalletResponse manualAdjustCoins(ManualCoinAdjustRequest request) {
        CoinWallet wallet = getOrCreateWalletWithLock(request.getUserId());
        BigDecimal amount = request.getAmount();

        CoinTransactionType type = request.getType();
        if (type == null) {
            type = CoinTransactionType.ADJUSTMENT;
        }

        BigDecimal newBalance = wallet.getBalance().add(amount);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            newBalance = BigDecimal.ZERO;
        }

        LocalDateTime now = LocalDateTime.now();
        if (wallet.getFirstCoinEarnedAt() == null && newBalance.compareTo(BigDecimal.ZERO) > 0) {
            wallet.setFirstCoinEarnedAt(now);
            wallet.setExpiryDate(now.plusDays(365));
        }

        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        CoinTransaction tx = new CoinTransaction();
        tx.setWallet(wallet);
        tx.setUserId(request.getUserId());
        tx.setType(type);
        tx.setAmount(amount);
        tx.setBalanceAfter(newBalance);
        tx.setRemainingAmount(amount.compareTo(BigDecimal.ZERO) > 0 ? amount : BigDecimal.ZERO);
        tx.setExpiryDate(wallet.getExpiryDate());
        tx.setDescription((request.getDescription() != null && !request.getDescription().isBlank())
                ? request.getDescription() : "Admin manual korreksiya");
        transactionRepository.save(tx);

        if (Boolean.TRUE.equals(request.getSendNotification())) {
            coinNotificationPublisher.sendPush(
                    request.getUserId(),
                    request.getNotificationTitle(),
                    request.getNotificationBody());
        }

        return getWalletInfo(request.getUserId());
    }

    @Override
    @Transactional
    public BulkCoinAdjustResponse bulkWelcomeBonus(BulkWelcomeBonusRequest request) {
        List<Long> pendingUserIds = identityBackendClient.findPendingWelcomeBonusUserIds();
        List<Long> successUserIds = new ArrayList<>();
        List<Long> failedUserIds = new ArrayList<>();

        for (Long userId : pendingUserIds) {
            try {
                var user = userGrpcClient.getUser(userId);
                WelcomeBonusRequest bonusRequest = WelcomeBonusRequest.builder()
                        .phone(user != null ? user.getMobile() : null)
                        .email(user != null ? user.getEmail() : null)
                        .notificationTitle(request.getNotificationTitle())
                        .notificationBody(request.getNotificationBody())
                        .sendNotification(request.getSendNotification())
                        .build();
                awardWelcomeBonus(userId, bonusRequest);
                successUserIds.add(userId);
            } catch (Exception e) {
                log.warn("Welcome bonus bulk send failed for userId={}: {}", userId, e.getMessage());
                failedUserIds.add(userId);
            }
        }

        return BulkCoinAdjustResponse.builder()
                .totalRequested(pendingUserIds.size())
                .totalSuccess(successUserIds.size())
                .totalFailed(failedUserIds.size())
                .successUserIds(successUserIds)
                .failedUserIds(failedUserIds)
                .build();
    }

    @Override
    @Transactional
    public BulkCoinAdjustResponse bulkAdjustCoins(BulkCoinAdjustRequest request) {
        List<Long> successUserIds = new ArrayList<>();
        List<Long> failedUserIds = new ArrayList<>();

        for (Long userId : request.getUserIds()) {
            try {
                ManualCoinAdjustRequest singleReq = ManualCoinAdjustRequest.builder()
                        .userId(userId)
                        .amount(request.getAmount())
                        .type(request.getType() != null ? request.getType() : CoinTransactionType.ADJUSTMENT)
                        .description(request.getDescription())
                        .notificationTitle(request.getNotificationTitle())
                        .notificationBody(request.getNotificationBody())
                        .sendNotification(request.getSendNotification())
                        .build();

                manualAdjustCoins(singleReq);
                successUserIds.add(userId);
            } catch (Exception e) {
                log.error("Error performing bulk coin adjustment for userId: {}", userId, e);
                failedUserIds.add(userId);
            }
        }

        return BulkCoinAdjustResponse.builder()
                .totalRequested(request.getUserIds().size())
                .totalSuccess(successUserIds.size())
                .totalFailed(failedUserIds.size())
                .successUserIds(successUserIds)
                .failedUserIds(failedUserIds)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CoinTransactionResponse> getAllTransactionsForAdmin(Pageable pageable) {
        return transactionRepository.findAllByOrderByCreatedDateDesc(pageable)
                .map(this::mapToTransactionResponse);
    }

    // Helper method to resolve actual package price dynamically from SubscriptionPackageGrpcClient
    private BigDecimal resolvePackagePrice(Long subscriptionPlanId, Long optionId, BigDecimal requestPrice) {
        if (subscriptionPlanId != null) {
            try {
                Long optId = optionId != null ? optionId : 1L;
                var details = subscriptionPackageGrpcClient.getOptionPriceCurrency(subscriptionPlanId, optId);
                if (details != null && details.amount > 0) {
                    return BigDecimal.valueOf(details.amount).setScale(2, RoundingMode.HALF_UP);
                }
            } catch (Exception e) {
                log.warn("Could not resolve price via SubscriptionPackageGrpcClient for planId: {}, falling back to request price", subscriptionPlanId);
            }
        }
        if (requestPrice != null && requestPrice.compareTo(BigDecimal.ZERO) > 0) {
            return requestPrice;
        }
        throw new BadRequestException("Paket qiyməti müəyyən edilə bilmədi (subscriptionPlanId icbaridir)");
    }

    private CoinWallet getOrCreateWallet(Long userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> {
                    CoinWallet newWallet = new CoinWallet();
                    newWallet.setUserId(userId);
                    newWallet.setBalance(BigDecimal.ZERO);
                    return walletRepository.save(newWallet);
                });
    }

    private CoinWallet getOrCreateWalletWithLock(Long userId) {
        return walletRepository.findByUserIdWithLock(userId)
                .orElseGet(() -> {
                    CoinWallet newWallet = new CoinWallet();
                    newWallet.setUserId(userId);
                    newWallet.setBalance(BigDecimal.ZERO);
                    return walletRepository.save(newWallet);
                });
    }

    private CoinSettings getSettingsInternal() {
        return settingsRepository.findFirstByActiveTrueOrderByIdDesc()
                .or(() -> settingsRepository.findFirstByOrderByIdDesc())
                .orElseThrow(() -> new IllegalStateException(
                        "Coin ayarları tapılmadı. Admin panelində Kampaniya bölməsindən konfiqurasiya edin."));
    }

    private CoinTransactionResponse mapToTransactionResponse(CoinTransaction tx) {
        CoinTransactionSourceType sourceType = resolveSourceType(tx);
        String sourceTitle = resolveSourceTitle(tx, sourceType);

        return CoinTransactionResponse.builder()
                .id(tx.getId())
                .type(tx.getType())
                .refundAction(tx.getRefundAction())
                .sourceType(sourceType)
                .sourceTitle(sourceTitle)
                .amount(tx.getAmount())
                .balanceAfter(tx.getBalanceAfter())
                .orderId(tx.getOrderId())
                .createdDate(tx.getCreatedDate())
                .build();
    }

    private CoinTransactionSourceType resolveSourceType(CoinTransaction tx) {
        return switch (tx.getType()) {
            case BONUS -> CoinTransactionSourceType.WELCOME_BONUS;
            case EARN -> CoinTransactionSourceType.SUBSCRIPTION_PURCHASE;
            case CAMPAIGN_BONUS -> CoinTransactionSourceType.CAMPAIGN;
            case SPEND -> CoinTransactionSourceType.SUBSCRIPTION_PURCHASE;
            case REFUND -> CoinTransactionSourceType.REFUND;
            case EXPIRE -> CoinTransactionSourceType.EXPIRY;
            case ADJUSTMENT -> CoinTransactionSourceType.MANUAL_ADJUSTMENT;
        };
    }

    private String resolveSourceTitle(CoinTransaction tx, CoinTransactionSourceType sourceType) {
        if (tx.getType() == CoinTransactionType.REFUND && tx.getRefundAction() != null) {
            return switch (tx.getRefundAction()) {
                case RESTORE_SPENT_COINS -> "Xərclənmiş Coin-lər geri qaytarıldı";
                case REVERSE_EARNED_COINS -> "Qazanılmış Coin-lər ləğv edildi";
            };
        }
        if (tx.getDescription() != null && !tx.getDescription().isBlank()) {
            return tx.getDescription();
        }
        return switch (sourceType) {
            case WELCOME_BONUS -> "Qeydiyyat bonusu";
            case SUBSCRIPTION_PURCHASE -> "Abunəlik alışı";
            case CAMPAIGN -> "Kampaniya bonusu";
            case MANUAL_ADJUSTMENT -> "Admin korreksiyası";
            case REFUND -> "Ödəniş geri qaytarıldı";
            case EXPIRY -> "Coin müddəti bitdi";
        };
    }

    private CoinSettingsResponse mapToSettingsResponse(CoinSettings settings) {
        return CoinSettingsResponse.builder()
                .id(settings.getId())
                .welcomeBonusAmount(settings.getWelcomeBonusAmount())
                .earnRateAznToCoin(settings.getEarnRateAznToCoin())
                .spendRateCoinToAzn(settings.getSpendRateCoinToAzn())
                .maxDiscountPercentage(settings.getMaxDiscountPercentage())
                .expiryMonths(settings.getExpiryMonths())
                .active(settings.getActive())
                .build();
    }

    private String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return input;
        }
    }
}
