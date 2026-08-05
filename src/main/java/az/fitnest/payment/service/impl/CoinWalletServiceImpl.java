package az.fitnest.payment.service.impl;

import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.exception.ConflictException;
import az.fitnest.payment.exception.ResourceNotFoundException;
import az.fitnest.payment.model.entity.CoinSettings;
import az.fitnest.payment.model.entity.CoinTransaction;
import az.fitnest.payment.model.entity.CoinWallet;
import az.fitnest.payment.model.entity.WelcomeBonusIdentifier;
import az.fitnest.payment.model.enums.CoinTransactionType;
import az.fitnest.payment.repository.CoinSettingsRepository;
import az.fitnest.payment.repository.CoinTransactionRepository;
import az.fitnest.payment.repository.CoinWalletRepository;
import az.fitnest.payment.repository.WelcomeBonusIdentifierRepository;
import az.fitnest.payment.service.CoinWalletService;
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
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoinWalletServiceImpl implements CoinWalletService {

    private final CoinWalletRepository walletRepository;
    private final CoinTransactionRepository transactionRepository;
    private final CoinSettingsRepository settingsRepository;
    private final WelcomeBonusIdentifierRepository welcomeBonusIdentifierRepository;

    @Override
    @Transactional(readOnly = true)
    public CoinWalletResponse getWalletInfo(Long userId) {
        CoinWallet wallet = getOrCreateWallet(userId);
        CoinSettings settings = getSettingsInternal();

        BigDecimal balance = wallet.getBalance();
        BigDecimal aznEquivalent = balance.divide(settings.getSpendRateCoinToAzn(), 2, RoundingMode.HALF_UP);

        LocalDateTime now = LocalDateTime.now();
        BigDecimal expiringSoon = transactionRepository.findExpiringSoonAmount(userId, now, now.plusDays(30));
        LocalDateTime nextExpiry = transactionRepository.findNextExpiryDate(userId, now).orElse(null);

        return CoinWalletResponse.builder()
                .totalBalance(balance)
                .aznEquivalent(aznEquivalent)
                .expiringSoonCoins(expiringSoon)
                .nextExpiryDate(nextExpiry)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CoinTransactionResponse> getTransactionHistory(Long userId, Pageable pageable) {
        return transactionRepository.findByUserIdOrderByCreatedDateDesc(userId, pageable)
                .map(this::mapToTransactionResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public CalculateDiscountResponse calculateCheckoutDiscount(Long userId, BigDecimal originalPrice, BigDecimal coinsToUse) {
        CoinSettings settings = getSettingsInternal();
        CoinWallet wallet = getOrCreateWallet(userId);

        BigDecimal availableCoins = wallet.getBalance();
        BigDecimal requestedCoins = (coinsToUse != null) ? coinsToUse.min(availableCoins) : BigDecimal.ZERO;
        if (requestedCoins.compareTo(BigDecimal.ZERO) < 0) {
            requestedCoins = BigDecimal.ZERO;
        }

        // Max discount allowed in AZN = originalPrice * (maxDiscountPercentage / 100)
        BigDecimal maxDiscountAzn = originalPrice
                .multiply(settings.getMaxDiscountPercentage())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        // Calculated discount in AZN from coins = requestedCoins / spendRateCoinToAzn
        BigDecimal rawDiscountAzn = requestedCoins.divide(settings.getSpendRateCoinToAzn(), 2, RoundingMode.HALF_UP);

        BigDecimal appliedDiscountAzn;
        BigDecimal effectiveCoinsDeducted;
        boolean isMaxDiscountReached = false;

        if (rawDiscountAzn.compareTo(maxDiscountAzn) > 0) {
            appliedDiscountAzn = maxDiscountAzn;
            effectiveCoinsDeducted = maxDiscountAzn.multiply(settings.getSpendRateCoinToAzn()).setScale(2, RoundingMode.HALF_UP);
            isMaxDiscountReached = true;
        } else {
            appliedDiscountAzn = rawDiscountAzn;
            effectiveCoinsDeducted = requestedCoins;
        }

        BigDecimal finalPaymentAmount = originalPrice.subtract(appliedDiscountAzn);
        if (finalPaymentAmount.compareTo(BigDecimal.ZERO) < 0) {
            finalPaymentAmount = BigDecimal.ZERO;
        }

        return CalculateDiscountResponse.builder()
                .originalPrice(originalPrice)
                .coinsToUse(effectiveCoinsDeducted)
                .appliedDiscountAzn(appliedDiscountAzn)
                .finalPaymentAmount(finalPaymentAmount)
                .maxDiscountLimitAzn(maxDiscountAzn)
                .isMaxDiscountReached(isMaxDiscountReached)
                .build();
    }

    @Override
    @Transactional
    public CoinWalletResponse awardWelcomeBonus(Long userId, WelcomeBonusRequest request) {
        CoinSettings settings = getSettingsInternal();

        String phoneHash = (request != null && request.getPhone() != null && !request.getPhone().isBlank())
                ? hashString(request.getPhone().trim()) : null;
        String emailHash = (request != null && request.getEmail() != null && !request.getEmail().isBlank())
                ? hashString(request.getEmail().trim().toLowerCase()) : null;

        if (welcomeBonusIdentifierRepository.existsByUserId(userId)) {
            throw new ConflictException("Welcome bonus bu istifadəçiyə artıq verilib");
        }
        if (phoneHash != null && welcomeBonusIdentifierRepository.existsByPhoneHash(phoneHash)) {
            throw new ConflictException("Bu telefon nömrəsinə Welcome bonus artıq verilib");
        }
        if (emailHash != null && welcomeBonusIdentifierRepository.existsByEmailHash(emailHash)) {
            throw new ConflictException("Bu e-poçt ünvanına Welcome bonus artıq verilib");
        }

        CoinWallet wallet = getOrCreateWalletWithLock(userId);
        BigDecimal bonusAmount = settings.getWelcomeBonusAmount();
        BigDecimal newBalance = wallet.getBalance().add(bonusAmount);
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        LocalDateTime expiryDate = LocalDateTime.now().plusMonths(settings.getExpiryMonths());

        CoinTransaction transaction = new CoinTransaction();
        transaction.setWallet(wallet);
        transaction.setUserId(userId);
        transaction.setType(CoinTransactionType.BONUS);
        transaction.setAmount(bonusAmount);
        transaction.setBalanceAfter(newBalance);
        transaction.setRemainingAmount(bonusAmount);
        transaction.setExpiryDate(expiryDate);
        transaction.setDescription("Welcome Bonus (50 Coin)");
        transactionRepository.save(transaction);

        WelcomeBonusIdentifier identifier = new WelcomeBonusIdentifier();
        identifier.setUserId(userId);
        identifier.setPhoneHash(phoneHash);
        identifier.setEmailHash(emailHash);
        welcomeBonusIdentifierRepository.save(identifier);

        log.info("Welcome bonus awarded to userId: {}, bonusAmount: {}", userId, bonusAmount);
        return getWalletInfo(userId);
    }

    @Override
    @Transactional
    public void processPaymentCoins(Long userId, String orderId, Long paymentId, BigDecimal coinsUsed, BigDecimal netPaidAmount) {
        CoinSettings settings = getSettingsInternal();
        CoinWallet wallet = getOrCreateWalletWithLock(userId);
        LocalDateTime now = LocalDateTime.now();

        // 1. Process Coins Spent (Earliest Expiration First)
        if (coinsUsed != null && coinsUsed.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal toDeduct = coinsUsed;
            List<CoinTransaction> activeBatches = transactionRepository.findActiveEarnBatchesForSpending(userId, now);

            for (CoinTransaction batch : activeBatches) {
                BigDecimal remaining = batch.getRemainingAmount();
                if (remaining == null || remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal take = toDeduct.min(remaining);
                batch.setRemainingAmount(remaining.subtract(take));
                transactionRepository.save(batch);

                toDeduct = toDeduct.subtract(take);
                if (toDeduct.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
            }

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

        // 2. Process Coins Earned (1 AZN net payment = 1 Coin)
        if (netPaidAmount != null && netPaidAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal earnedCoins = netPaidAmount.multiply(settings.getEarnRateAznToCoin()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal newBalance = wallet.getBalance().add(earnedCoins);
            wallet.setBalance(newBalance);

            LocalDateTime expiryDate = now.plusMonths(settings.getExpiryMonths());

            CoinTransaction earnTx = new CoinTransaction();
            earnTx.setWallet(wallet);
            earnTx.setUserId(userId);
            earnTx.setType(CoinTransactionType.EARN);
            earnTx.setAmount(earnedCoins);
            earnTx.setBalanceAfter(newBalance);
            earnTx.setRemainingAmount(earnedCoins);
            earnTx.setExpiryDate(expiryDate);
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
        LocalDateTime now = LocalDateTime.now();

        // 1. Return spent coins to user's wallet
        if (coinsOriginallySpent != null && coinsOriginallySpent.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal newBalance = wallet.getBalance().add(coinsOriginallySpent);
            wallet.setBalance(newBalance);

            CoinTransaction refundSpendTx = new CoinTransaction();
            refundSpendTx.setWallet(wallet);
            refundSpendTx.setUserId(userId);
            refundSpendTx.setType(CoinTransactionType.REFUND);
            refundSpendTx.setAmount(coinsOriginallySpent);
            refundSpendTx.setBalanceAfter(newBalance);
            refundSpendTx.setRemainingAmount(coinsOriginallySpent);
            refundSpendTx.setExpiryDate(now.plusMonths(12));
            refundSpendTx.setOrderId(orderId);
            refundSpendTx.setPaymentId(paymentId);
            refundSpendTx.setDescription("Ləğv edilən ödəniş üzrə xərclənmiş Coin-lər geri qaytarıldı");
            transactionRepository.save(refundSpendTx);
        }

        // 2. Revoke earned coins (Ensure balance doesn't drop below 0 per BR-10 & PM response)
        if (coinsOriginallyEarned != null && coinsOriginallyEarned.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal toRevoke = wallet.getBalance().min(coinsOriginallyEarned);
            BigDecimal newBalance = wallet.getBalance().subtract(toRevoke);
            wallet.setBalance(newBalance);

            CoinTransaction refundEarnTx = new CoinTransaction();
            refundEarnTx.setWallet(wallet);
            refundEarnTx.setUserId(userId);
            refundEarnTx.setType(CoinTransactionType.REFUND);
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
        List<CoinTransaction> expiredBatches = transactionRepository.findExpiredBatches(now);

        for (CoinTransaction batch : expiredBatches) {
            BigDecimal amountToExpire = batch.getRemainingAmount();
            if (amountToExpire == null || amountToExpire.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            batch.setRemainingAmount(BigDecimal.ZERO);
            transactionRepository.save(batch);

            CoinWallet wallet = getOrCreateWalletWithLock(batch.getUserId());
            BigDecimal actualDeduct = wallet.getBalance().min(amountToExpire);
            BigDecimal newBalance = wallet.getBalance().subtract(actualDeduct);
            wallet.setBalance(newBalance);
            walletRepository.save(wallet);

            CoinTransaction expireTx = new CoinTransaction();
            expireTx.setWallet(wallet);
            expireTx.setUserId(batch.getUserId());
            expireTx.setType(CoinTransactionType.EXPIRE);
            expireTx.setAmount(actualDeduct.negate());
            expireTx.setBalanceAfter(newBalance);
            expireTx.setRemainingAmount(BigDecimal.ZERO);
            expireTx.setDescription("İstifadə müddəti bitmiş Coin-lər silindi");
            transactionRepository.save(expireTx);

            log.info("Expired {} coins for userId: {}", actualDeduct, batch.getUserId());
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
        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        CoinTransaction tx = new CoinTransaction();
        tx.setWallet(wallet);
        tx.setUserId(request.getUserId());
        tx.setType(type);
        tx.setAmount(amount);
        tx.setBalanceAfter(newBalance);
        tx.setRemainingAmount(amount.compareTo(BigDecimal.ZERO) > 0 ? amount : BigDecimal.ZERO);
        tx.setExpiryDate(LocalDateTime.now().plusMonths(getSettingsInternal().getExpiryMonths()));
        tx.setDescription((request.getDescription() != null && !request.getDescription().isBlank())
                ? request.getDescription() : "Admin manual korreksiya");
        transactionRepository.save(tx);

        return getWalletInfo(request.getUserId());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CoinTransactionResponse> getAllTransactionsForAdmin(Pageable pageable) {
        return transactionRepository.findAllByOrderByCreatedDateDesc(pageable)
                .map(this::mapToTransactionResponse);
    }

    // Helper methods
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
                .orElseGet(() -> {
                    CoinSettings defaultSettings = new CoinSettings();
                    return settingsRepository.save(defaultSettings);
                });
    }

    private CoinTransactionResponse mapToTransactionResponse(CoinTransaction tx) {
        return CoinTransactionResponse.builder()
                .id(tx.getId())
                .type(tx.getType())
                .amount(tx.getAmount())
                .balanceAfter(tx.getBalanceAfter())
                .orderId(tx.getOrderId())
                .expiryDate(tx.getExpiryDate())
                .description(tx.getDescription())
                .createdDate(tx.getCreatedDate())
                .build();
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
