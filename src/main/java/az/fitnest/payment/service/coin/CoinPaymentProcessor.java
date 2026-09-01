package az.fitnest.payment.service.coin;

import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.enums.CoinTransactionType;
import az.fitnest.payment.repository.CoinTransactionRepository;
import az.fitnest.payment.service.CoinWalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Applies coin earn/spend side effects when a subscription payment succeeds or is refunded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoinPaymentProcessor {

    private final CoinWalletService coinWalletService;
    private final CoinTransactionRepository coinTransactionRepository;

    @Transactional
    public void onPaymentSuccess(Payment payment) {
        if (payment == null || payment.getUserId() == null || payment.getOrderId() == null) {
            return;
        }
        if (!"SUCCESS".equalsIgnoreCase(payment.getStatus())) {
            return;
        }
        if (alreadyProcessed(payment, List.of(CoinTransactionType.EARN, CoinTransactionType.SPEND))) {
            log.debug("[Coin] Skip duplicate success processing for orderId={}", payment.getOrderId());
            return;
        }

        BigDecimal coinsUsed = payment.getCoinsUsed() != null ? payment.getCoinsUsed() : BigDecimal.ZERO;
        BigDecimal netPaid = toAzn(payment.getAmount());
        coinWalletService.processPaymentCoins(
                payment.getUserId(),
                payment.getOrderId(),
                payment.getId(),
                coinsUsed,
                netPaid);
        log.info("[Coin] Processed success orderId={} coinsUsed={} netPaidAzn={}",
                payment.getOrderId(), coinsUsed, netPaid);
    }

    @Transactional
    public void onPaymentRefund(Payment payment) {
        if (payment == null || payment.getUserId() == null || payment.getOrderId() == null) {
            return;
        }
        if (alreadyProcessed(payment, List.of(CoinTransactionType.REFUND))) {
            log.debug("[Coin] Skip duplicate refund processing for orderId={}", payment.getOrderId());
            return;
        }

        BigDecimal coinsUsed = payment.getCoinsUsed() != null ? payment.getCoinsUsed() : BigDecimal.ZERO;
        BigDecimal netPaid = toAzn(payment.getAmount());
        var settings = coinWalletService.getSettings();
        BigDecimal earnRate = settings.getEarnRateAznToCoin() != null
                ? settings.getEarnRateAznToCoin()
                : BigDecimal.ONE;
        BigDecimal coinsEarned = netPaid.multiply(earnRate).setScale(2, RoundingMode.HALF_UP);

        coinWalletService.processRefundCoins(
                payment.getUserId(),
                payment.getOrderId(),
                payment.getId(),
                coinsUsed,
                coinsEarned);
        log.info("[Coin] Processed refund orderId={} restoreCoins={} revokeEarned={}",
                payment.getOrderId(), coinsUsed, coinsEarned);
    }

    private boolean alreadyProcessed(Payment payment, List<CoinTransactionType> types) {
        return !coinTransactionRepository
                .findByUserIdAndOrderIdAndTypeIn(payment.getUserId(), payment.getOrderId(), types)
                .isEmpty();
    }

    private static BigDecimal toAzn(Double amount) {
        if (amount == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }
}
