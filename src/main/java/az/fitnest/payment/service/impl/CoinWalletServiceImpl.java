package az.fitnest.payment.service.impl;

import az.fitnest.payment.client.IdentityBackendClient;
import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.UserGrpcClient;
import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.event.PaymentOutboxService;
import az.fitnest.payment.exception.BadRequestException;
import az.fitnest.payment.exception.ConflictException;
import az.fitnest.payment.model.entity.CoinSettings;
import az.fitnest.payment.model.entity.CoinTransaction;
import az.fitnest.payment.model.entity.CoinWallet;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.WelcomeBonusIdentifier;
import az.fitnest.payment.model.enums.CoinRefundAction;
import az.fitnest.payment.model.enums.CoinTransactionCategory;
import az.fitnest.payment.model.enums.CoinTransactionSourceType;
import az.fitnest.payment.model.enums.CoinTransactionType;
import az.fitnest.payment.repository.CoinSettingsRepository;
import az.fitnest.payment.repository.CoinTransactionRepository;
import az.fitnest.payment.repository.CoinWalletRepository;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.WelcomeBonusIdentifierRepository;
import az.fitnest.payment.service.CoinWalletService;
import az.fitnest.payment.service.coin.CoinEarnCalculator;
import az.fitnest.payment.service.coin.CoinNotificationPublisher;
import az.fitnest.payment.util.PaymentPackageRef;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final CoinEarnCalculator coinEarnCalculator;
    private final PaymentRepository paymentRepository;
    private final PaymentOutboxService paymentOutboxService;

    @Override
    @Transactional(readOnly = true)
    public CoinWalletResponse getWalletInfo(Long userId) {
        CoinWallet wallet = getWalletOrEmpty(userId);
        CoinSettings settings = getSettingsInternal();

        BigDecimal balance = wallet.getBalance();
        BigDecimal spendRate = settings.getSpendRateCoinToAzn();
        BigDecimal aznEquivalent = BigDecimal.ZERO;
        if (spendRate != null && spendRate.compareTo(BigDecimal.ZERO) > 0) {
            aznEquivalent = balance.divide(spendRate, 2, RoundingMode.HALF_UP);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiryDate = wallet.getExpiryDate();
        if (expiryDate == null && wallet.getFirstCoinEarnedAt() != null) {
            expiryDate = computeExpiryDate(wallet.getFirstCoinEarnedAt(), settings);
        }
        Long daysUntilExpiry = null;
        if (expiryDate != null) {
            long days = Duration.between(now, expiryDate).toDays();
            daysUntilExpiry = days > 0 ? days : 0L;
        }

        return CoinWalletResponse.builder()
                .totalBalance(balance)
                .aznEquivalent(aznEquivalent)
                .firstCoinEarnedAt(wallet.getFirstCoinEarnedAt())
                .expiryDate(expiryDate)
                .daysUntilExpiry(daysUntilExpiry)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public CoinBalanceResponse getCoinBalance(Long userId) {
        CoinSettings settings = getSettingsInternal();
        CoinWallet wallet = getWalletOrEmpty(userId);

        BigDecimal coinBalance = wallet.getBalance();
        BigDecimal spendRate = settings.getSpendRateCoinToAzn();
        BigDecimal aznEquivalent = BigDecimal.ZERO;
        if (spendRate != null && spendRate.compareTo(BigDecimal.ZERO) > 0) {
            aznEquivalent = coinBalance.divide(spendRate, 2, RoundingMode.HALF_UP);
        }

        var welcomeIdentifier = welcomeBonusIdentifierRepository.findByUserId(userId);
        boolean welcomeBonusAwarded = welcomeIdentifier.isPresent();
        boolean showWelcomeBonusPopup = welcomeIdentifier
                .map(identifier -> !identifier.isPopupShown())
                .orElse(false);

        return CoinBalanceResponse.builder()
                .coinBalance(coinBalance)
                .aznEquivalent(aznEquivalent)
                .welcomeBonusAwarded(welcomeBonusAwarded)
                .showWelcomeBonusPopup(showWelcomeBonusPopup)
                .welcomeBonusAmount(showWelcomeBonusPopup ? settings.getWelcomeBonusAmount() : null)
                .build();
    }

    @Override
    @Transactional
    public void markWelcomeBonusPopupShown(Long userId) {
        welcomeBonusIdentifierRepository.findByUserId(userId).ifPresent(identifier -> {
            if (!identifier.isPopupShown()) {
                identifier.setPopupShown(true);
                welcomeBonusIdentifierRepository.save(identifier);
            }
        });
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
        CoinWallet wallet = getWalletOrEmpty(userId);

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
    @Transactional(readOnly = true)
    public FullPaymentEligibilityResponse checkFullPaymentEligibility(Long userId, FullPaymentEligibilityRequest request) {
        CoinBalanceResponse balance = getCoinBalance(userId);
        BigDecimal packagePrice = resolvePackagePrice(request.getPackageId(), request.getOptionId(), null);
        boolean isEligible = balance.getAznEquivalent().compareTo(packagePrice) >= 0;

        return FullPaymentEligibilityResponse.builder()
                .isEligibleForFullPayment(isEligible)
                .build();
    }

    @Override
    @Transactional
    public PayFullWithCoinsResponse payFullWithCoins(Long userId, PayFullWithCoinsRequest request) {
        CoinSettings settings = getSettingsInternal();
        CoinWallet wallet = getOrCreateWalletWithLock(userId);

        Long packageId = request.getSubscriptionPlanId();
        Long optionId = request.getOptionId();
        if (packageId == null || optionId == null) {
            throw new BadRequestException("subscriptionPlanId və optionId icbaridir");
        }

        var priceDetails = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
        if (priceDetails == null || priceDetails.amount <= 0) {
            throw new BadRequestException("Paket qiyməti müəyyən edilə bilmədi");
        }
        BigDecimal originalPrice = BigDecimal.valueOf(priceDetails.amount).setScale(2, RoundingMode.HALF_UP);
        int durationMonths = priceDetails.durationMonths > 0 ? priceDetails.durationMonths : 1;
        BigDecimal coinsNeeded = originalPrice.multiply(settings.getSpendRateCoinToAzn()).setScale(2, RoundingMode.HALF_UP);

        if (wallet.getBalance().compareTo(coinsNeeded) < 0) {
            throw new BadRequestException("100% Coin ilə ödəniş üçün balansınızda kifayət qədər Coin yoxdur");
        }

        BigDecimal newBalance = wallet.getBalance().subtract(coinsNeeded);
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        String orderId = "COIN-ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String description = PaymentPackageRef.encode(packageId, optionId);
        String historyTitle = buildSubscriptionHistoryTitle(packageId, optionId);

        CoinTransaction spendTx = new CoinTransaction();
        spendTx.setWallet(wallet);
        spendTx.setUserId(userId);
        spendTx.setType(CoinTransactionType.SPEND);
        spendTx.setAmount(coinsNeeded.negate());
        spendTx.setBalanceAfter(newBalance);
        spendTx.setOrderId(orderId);
        spendTx.setRemainingAmount(BigDecimal.ZERO);
        spendTx.setDescription(historyTitle);
        transactionRepository.save(spendTx);

        Payment payment = new Payment();
        payment.setProvider("COIN");
        payment.setOrderId(orderId);
        payment.setTransactionId(orderId);
        payment.setAmount(0.0);
        payment.setCurrency("AZN");
        payment.setStatus("SUCCESS");
        payment.setUserId(userId);
        payment.setDescription(description);
        payment.setType("FULL_COIN_PAYMENT");
        payment.setAutoPaymentEnabled(false);
        payment.setCoinsUsed(coinsNeeded);
        payment.setCallbackProcessed(true);
        payment.setMessage("Paid fully with FitNest Coins");
        payment = paymentRepository.save(payment);

        spendTx.setPaymentId(payment.getId());
        transactionRepository.save(spendTx);

        // Same durable assign path as bank payments (OutboxRelay → order-backend gRPC).
        paymentOutboxService.recordPaymentOutcome(payment);
        paymentOutboxService.requestSubscriptionAssignment(
                userId, packageId, optionId, false, orderId);

        log.info("Full coin payment executed for userId: {}, planId: {}, optionId: {}, durationMonths: {}, coinsDeducted: {}, orderId: {}",
                userId, packageId, optionId, durationMonths, coinsNeeded, orderId);

        return PayFullWithCoinsResponse.builder()
                .success(true)
                .orderId(orderId)
                .subscriptionPlanId(packageId)
                .optionId(optionId)
                .durationMonths(durationMonths)
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
            wallet.setExpiryDate(computeExpiryDate(now, getSettingsInternal()));
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

        // Identifier save is the source of truth for idempotency. Identity flag is best-effort
        // so a transient IAM outage cannot roll back an already-credited wallet.
        try {
            identityBackendClient.markWelcomeBonusReceived(userId);
        } catch (Exception e) {
            log.error("Welcome bonus credited for userId={} but identity flag update failed: {}",
                    userId, e.getMessage());
        }

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
    public void processPaymentCoins(Long userId, String orderId, Long paymentId, BigDecimal coinsUsed,
                                     BigDecimal netPaidAmount, Long packageId, Long optionId) {
        CoinSettings settings = getSettingsInternal();
        CoinWallet wallet = getOrCreateWalletWithLock(userId);
        LocalDateTime now = LocalDateTime.now();

        if (wallet.getFirstCoinEarnedAt() == null && (
                (coinsUsed != null && coinsUsed.compareTo(BigDecimal.ZERO) > 0) ||
                (netPaidAmount != null && netPaidAmount.compareTo(BigDecimal.ZERO) > 0))) {
            wallet.setFirstCoinEarnedAt(now);
            wallet.setExpiryDate(computeExpiryDate(now, settings));
        }

        String historyTitle = buildSubscriptionHistoryTitle(packageId, optionId);

        // 1. Process Coins Spent (partial coin discount on card / payment-method checkout)
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
            spendTx.setDescription(historyTitle);
            transactionRepository.save(spendTx);
        }

        // 2. Process Coins Earned — only on non-coin cash portion
        if (netPaidAmount != null && netPaidAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal earnedCoins;
            CoinEarnCalculator.EarnResult earnResult = null;

            if (coinEarnCalculator.isV2Formula(settings)) {
                try {
                    PackageContext ctx = resolvePackageContext(packageId, optionId, null, null);
                    if (ctx.packageId() == null || ctx.durationMonths() == null) {
                        log.warn("[Coin] Skip earn — could not resolve packageId={} optionId={}",
                                packageId, optionId);
                        earnedCoins = BigDecimal.ZERO;
                    } else {
                        earnResult = coinEarnCalculator.calculateV2(
                                netPaidAmount,
                                ctx.packageId(),
                                ctx.packageName(),
                                ctx.durationMonths(),
                                settings,
                                settings.getTierMultipliers(),
                                settings.getPeriodMultipliers());
                        earnedCoins = BigDecimal.valueOf(earnResult.getAwardedCoins());
                    }
                } catch (Exception e) {
                    log.warn("[Coin] Skip earn for packageId={} optionId={}: {}",
                            packageId, optionId, e.getMessage());
                    earnedCoins = BigDecimal.ZERO;
                }
            } else {
                earnedCoins = coinEarnCalculator.calculateV1(netPaidAmount, settings.getEarnRateAznToCoin());
            }

            if (earnedCoins.compareTo(BigDecimal.ZERO) > 0) {
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
                earnTx.setDescription(historyTitle);
                if (earnResult != null) {
                    earnTx.setFormulaVersion(earnResult.getFormulaVersion());
                    earnTx.setEligibleCashAmount(earnResult.getEligibleCashAmount());
                    earnTx.setRawCoins(earnResult.getRawCoins());
                    earnTx.setAwardedCoins(earnResult.getAwardedCoins());
                    earnTx.setEarnBreakdown(buildEarnBreakdown(earnResult));
                }
                transactionRepository.save(earnTx);
            }
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
    @Transactional
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
        CoinSettings settings = getSettingsInternal();
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
            wallet.setExpiryDate(computeExpiryDate(now, settings));
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

    /** Read-only paths: return existing wallet or an unsaved zero-balance view (no INSERT). */
    private CoinWallet getWalletOrEmpty(Long userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> {
                    CoinWallet emptyWallet = new CoinWallet();
                    emptyWallet.setUserId(userId);
                    emptyWallet.setBalance(BigDecimal.ZERO);
                    return emptyWallet;
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

    private LocalDateTime computeExpiryDate(LocalDateTime from, CoinSettings settings) {
        int months = settings.getExpiryMonths() != null && settings.getExpiryMonths() > 0
                ? settings.getExpiryMonths()
                : 12;
        return from.plusMonths(months);
    }

    private CoinSettings getSettingsInternal() {
        return settingsRepository.findFirstByActiveTrueOrderByIdDesc()
                .or(() -> settingsRepository.findFirstByOrderByIdDesc())
                .orElseGet(this::createDefaultSettings);
    }

    private CoinSettings createDefaultSettings() {
        CoinSettings settings = new CoinSettings();
        settings.setWelcomeBonusAmount(BigDecimal.ZERO);
        settings.setEarnRateAznToCoin(BigDecimal.ZERO);
        settings.setSpendRateCoinToAzn(new BigDecimal("10.00"));
        settings.setMaxDiscountPercentage(BigDecimal.ZERO);
        settings.setExpiryMonths(12);
        settings.setActive(false);
        settings.setFormulaVersion(CoinEarnCalculator.FORMULA_V2);
        settings.setBaseEarnRate(new BigDecimal("0.020000"));
        settings.setMaxGivebackRate(new BigDecimal("0.050000"));
        settings.setEarnCoinFactor(new BigDecimal("10.00"));
        settings.setTierMultipliers(new HashMap<>());
        settings.setPeriodMultipliers(new HashMap<>());
        return settingsRepository.save(settings);
    }

    private CoinTransactionResponse mapToTransactionResponse(CoinTransaction tx) {
        Payment linkedPayment = resolveLinkedPayment(tx);
        CoinTransactionSourceType sourceType = resolveSourceType(tx);
        String sourceTitle = resolveSourceTitle(tx, sourceType, linkedPayment);

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
            case EARN, SPEND -> CoinTransactionSourceType.SUBSCRIPTION_PURCHASE;
            case CAMPAIGN_BONUS -> CoinTransactionSourceType.CAMPAIGN;
            case REFUND -> CoinTransactionSourceType.REFUND;
            case EXPIRE -> CoinTransactionSourceType.EXPIRY;
            case ADJUSTMENT -> CoinTransactionSourceType.MANUAL_ADJUSTMENT;
        };
    }

    private String resolveSourceTitle(CoinTransaction tx, CoinTransactionSourceType sourceType, Payment linkedPayment) {
        if (tx.getType() == CoinTransactionType.REFUND && tx.getRefundAction() != null) {
            return switch (tx.getRefundAction()) {
                case RESTORE_SPENT_COINS -> "Xərclənmiş Coin-lər geri qaytarıldı";
                case REVERSE_EARNED_COINS -> "Qazanılmış Coin-lər ləğv edildi";
            };
        }
        if (tx.getDescription() != null && !tx.getDescription().isBlank() && !isGenericHistoryDescription(tx.getDescription())) {
            return tx.getDescription();
        }
        String fromPayment = resolveSubscriptionTitleFromPayment(linkedPayment);
        if (fromPayment != null) {
            return fromPayment;
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

    private Payment resolveLinkedPayment(CoinTransaction tx) {
        if (tx.getPaymentId() == null) {
            return null;
        }
        return paymentRepository.findById(tx.getPaymentId()).orElse(null);
    }

    private boolean isGenericHistoryDescription(String description) {
        return "Checkout endirimi üçün xərcləndi".equals(description)
                || "Uğurlu ödəniş üzrə Coin qazanıldı".equals(description)
                || "100% Coin ilə paket ödənişi edildi".equals(description);
    }

    private String resolveSubscriptionTitleFromPayment(Payment payment) {
        if (payment == null || payment.getDescription() == null) {
            return null;
        }
        PaymentPackageRef.Ref ref = PaymentPackageRef.parse(payment.getDescription());
        if (ref.packageId() == null) {
            return null;
        }
        return buildSubscriptionHistoryTitle(ref.packageId(), ref.optionId());
    }

    private String buildSubscriptionHistoryTitle(Long packageId, Long optionId) {
        PackageContext ctx = resolvePackageContext(packageId, optionId, null, null);
        String name = (ctx.packageName() != null && !ctx.packageName().isBlank())
                ? ctx.packageName().trim()
                : "Abunəlik";
        int months = ctx.durationMonths() != null && ctx.durationMonths() > 0 ? ctx.durationMonths() : 1;
        return name + " - " + months + " aylıq abunəlik";
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

    @Override
    @Transactional(readOnly = true)
    public CoinSettingsV2Response getSettingsV2() {
        return mapToSettingsV2Response(getSettingsInternal());
    }

    @Override
    @Transactional
    public CoinSettingsV2Response updateSettingsV2(CoinSettingsV2Request request) {
        CoinSettings settings = getSettingsInternal();
        settings.setFormulaVersion(
                request.getFormulaVersion() != null && !request.getFormulaVersion().isBlank()
                        ? request.getFormulaVersion()
                        : CoinEarnCalculator.FORMULA_V2);
        settings.setActive(request.getActive());
        settings.setWelcomeBonusAmount(request.getWelcomeBonusAmount());
        settings.setBaseEarnRate(request.getBaseEarnRate());
        settings.setMaxGivebackRate(request.getMaxGivebackRate());
        settings.setEarnCoinFactor(request.getEarnCoinFactor());
        settings.setSpendRateCoinToAzn(request.getSpendRateCoinToAzn());
        settings.setMaxDiscountPercentage(request.getMaxDiscountPercentage());
        settings.setExpiryMonths(request.getExpiryMonths());
        settings.setTierMultipliers(normalizeTierMultipliers(request.getTierMultipliers()));
        settings.setPeriodMultipliers(normalizePeriodMultipliers(request.getPeriodMultipliers()));
        CoinSettings saved = settingsRepository.save(settings);
        return mapToSettingsV2Response(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CoinEarnPreviewResponse previewEarn(CoinEarnPreviewRequest request) {
        CoinSettings settings = getSettingsInternal();
        PackageContext ctx = resolvePackageContext(
                request.getPackageId(),
                request.getOptionId(),
                request.getTierName(),
                request.getDurationMonths());

        CoinEarnCalculator.EarnResult result = coinEarnCalculator.calculateV2(
                request.getEligibleCashAmount(),
                ctx.packageId(),
                ctx.packageName(),
                ctx.durationMonths(),
                settings,
                settings.getTierMultipliers(),
                settings.getPeriodMultipliers());

        return mapToEarnPreviewResponse(result, request.getEligibleCashAmount());
    }

    @Override
    @Transactional(readOnly = true)
    public CoinEarnPreviewBatchResponse previewEarnBatch(CoinEarnPreviewBatchRequest request) {
        CoinSettings settings = getSettingsInternal();
        List<CoinEarnPreviewBatchResponse.PackageEarnPreview> previews = new ArrayList<>();

        for (CoinEarnPreviewBatchRequest.PackageEarnItem item : request.getPackages()) {
            try {
                PackageContext ctx = resolvePackageContext(
                        item.getPackageId(),
                        item.getOptionId(),
                        item.getTierName(),
                        item.getDurationMonths());

                BigDecimal price = item.getPriceAzn() != null
                        ? item.getPriceAzn()
                        : resolvePackagePrice(item.getPackageId(), item.getOptionId(), null);

                CoinEarnCalculator.EarnResult result = coinEarnCalculator.calculateV2(
                        price,
                        ctx.packageId(),
                        ctx.packageName(),
                        ctx.durationMonths(),
                        settings,
                        settings.getTierMultipliers(),
                        settings.getPeriodMultipliers());

                previews.add(CoinEarnPreviewBatchResponse.PackageEarnPreview.builder()
                        .packageId(item.getPackageId() != null ? item.getPackageId() : ctx.packageId())
                        .optionId(item.getOptionId())
                        .tier(ctx.packageName())
                        .durationMonths(ctx.durationMonths())
                        .priceAzn(price)
                        .appliedGivebackRate(result.getAppliedGivebackRate())
                        .awardedCoins(result.getAwardedCoins())
                        .build());
            } catch (Exception e) {
                log.warn("[Coin] Preview skipped packageId={} optionId={}: {}",
                        item.getPackageId(), item.getOptionId(), e.getMessage());
                previews.add(CoinEarnPreviewBatchResponse.PackageEarnPreview.builder()
                        .packageId(item.getPackageId())
                        .optionId(item.getOptionId())
                        .tier(item.getTierName())
                        .durationMonths(item.getDurationMonths())
                        .priceAzn(item.getPriceAzn())
                        .appliedGivebackRate(BigDecimal.ZERO)
                        .awardedCoins(0)
                        .build());
            }
        }

        return CoinEarnPreviewBatchResponse.builder()
                .formulaVersion(settings.getFormulaVersion())
                .previews(previews)
                .build();
    }

    private CoinSettingsV2Response mapToSettingsV2Response(CoinSettings settings) {
        return CoinSettingsV2Response.builder()
                .id(settings.getId())
                .formulaVersion(settings.getFormulaVersion() != null
                        ? settings.getFormulaVersion() : CoinEarnCalculator.FORMULA_V2)
                .active(settings.getActive())
                .welcomeBonusAmount(settings.getWelcomeBonusAmount())
                .baseEarnRate(settings.getBaseEarnRate() != null
                        ? settings.getBaseEarnRate() : new BigDecimal("0.020000"))
                .maxGivebackRate(settings.getMaxGivebackRate() != null
                        ? settings.getMaxGivebackRate() : new BigDecimal("0.050000"))
                .earnCoinFactor(settings.getEarnCoinFactor() != null
                        ? settings.getEarnCoinFactor() : new BigDecimal("10.00"))
                .spendRateCoinToAzn(settings.getSpendRateCoinToAzn())
                .maxDiscountPercentage(settings.getMaxDiscountPercentage())
                .expiryMonths(settings.getExpiryMonths())
                .tierMultipliers(CoinEarnCalculator.copyTierMap(settings.getTierMultipliers()))
                .periodMultipliers(settings.getPeriodMultipliers() != null
                        ? new HashMap<>(settings.getPeriodMultipliers()) : new HashMap<>())
                .build();
    }

    private CoinEarnPreviewResponse mapToEarnPreviewResponse(CoinEarnCalculator.EarnResult result, BigDecimal price) {
        return CoinEarnPreviewResponse.builder()
                .formulaVersion(result.getFormulaVersion())
                .tier(result.getPackageName())
                .durationMonths(result.getDurationMonths())
                .finalPackagePrice(price)
                .eligibleCashAmount(result.getEligibleCashAmount())
                .baseEarnRate(result.getBaseEarnRate())
                .tierMultiplier(result.getTierMultiplier())
                .periodMultiplier(result.getPeriodMultiplier())
                .rawGivebackRate(result.getRawGivebackRate())
                .appliedGivebackRate(result.getAppliedGivebackRate())
                .earnCoinFactor(result.getEarnCoinFactor())
                .rawCoins(result.getRawCoins())
                .awardedCoins(result.getAwardedCoins())
                .build();
    }

    private record PackageContext(Long packageId, String packageName, Integer durationMonths) {}

    private PackageContext resolvePackageContext(Long packageId, Long optionId, String tierName, Integer durationMonths) {
        Long resolvedPackageId = packageId;
        String resolvedName = tierName != null && !tierName.isBlank() ? tierName.trim() : null;
        Integer resolvedDuration = durationMonths;

        if (packageId != null && optionId != null) {
            try {
                var names = subscriptionPackageGrpcClient.getPackageNamesByIds(List.of(packageId));
                String rawName = names.stream()
                        .filter(pkg -> pkg.getPackageId() == packageId)
                        .map(az.fitnest.order.grpc.PackageNameInfo::getName)
                        .filter(name -> name != null && !name.isBlank())
                        .findFirst()
                        .orElse(null);
                if (rawName != null) {
                    resolvedName = rawName.trim();
                }
                var option = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
                if (option != null && option.durationMonths > 0) {
                    resolvedDuration = option.durationMonths;
                }
                resolvedPackageId = packageId;
            } catch (Exception e) {
                log.warn("Could not resolve package context for packageId={}, optionId={}", packageId, optionId, e);
            }
        }

        return new PackageContext(resolvedPackageId, resolvedName, resolvedDuration);
    }

    private Map<String, BigDecimal> normalizeTierMultipliers(Map<String, BigDecimal> input) {
        return CoinEarnCalculator.copyTierMap(input);
    }

    private Map<Integer, BigDecimal> normalizePeriodMultipliers(Map<Integer, BigDecimal> input) {
        return input != null ? new HashMap<>(input) : new HashMap<>();
    }

    private String buildEarnBreakdown(CoinEarnCalculator.EarnResult result) {
        return String.format(
                "packageId=%s,package=%s,duration=%s,base=%s,tierMult=%s,periodMult=%s,appliedRate=%s,factor=%s,eligible=%s,raw=%s,awarded=%s",
                result.getPackageId(),
                result.getPackageName(),
                result.getDurationMonths(),
                result.getBaseEarnRate(),
                result.getTierMultiplier(),
                result.getPeriodMultiplier(),
                result.getAppliedGivebackRate(),
                result.getEarnCoinFactor(),
                result.getEligibleCashAmount(),
                result.getRawCoins(),
                result.getAwardedCoins());
    }
}
