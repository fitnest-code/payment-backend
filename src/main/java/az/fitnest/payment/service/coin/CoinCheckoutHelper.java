package az.fitnest.payment.service.coin;

import az.fitnest.payment.dto.coin.CalculateDiscountRequest;
import az.fitnest.payment.dto.coin.CalculateDiscountResponse;
import az.fitnest.payment.service.CoinWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Applies FitNest Coin discount for subscription checkout when coin payment is enabled
 * ({@code isCoinUsed} / {@code coinPaymentEnabled} = true) or when auto-pay is enabled
 * (renewals always spend the maximum available coin balance).
 * Final bank charge = package option price − coin AZN equivalent (capped at price).
 */
@Service
@RequiredArgsConstructor
public class CoinCheckoutHelper {

    private final CoinWalletService coinWalletService;

    public record AppliedCheckout(
            BigDecimal originalPriceAzn,
            double finalAmountAzn,
            BigDecimal coinsUsed
    ) {
        public static AppliedCheckout noCoins(double amountAzn) {
            BigDecimal price = BigDecimal.valueOf(amountAzn).setScale(2, java.math.RoundingMode.HALF_UP);
            return new AppliedCheckout(price, amountAzn, BigDecimal.ZERO);
        }
    }

    public AppliedCheckout applyForSubscription(
            Long userId,
            Long packageId,
            Long optionId,
            Boolean coinPaymentEnabled,
            double originalAmountAzn) {
        return applyForSubscription(userId, packageId, optionId, coinPaymentEnabled, false, originalAmountAzn);
    }

    public AppliedCheckout applyForSubscription(
            Long userId,
            Long packageId,
            Long optionId,
            Boolean coinPaymentEnabled,
            Boolean autoPaymentEnabled,
            double originalAmountAzn) {
        boolean useCoins = Boolean.TRUE.equals(coinPaymentEnabled) || Boolean.TRUE.equals(autoPaymentEnabled);
        if (userId == null || !useCoins) {
            return AppliedCheckout.noCoins(originalAmountAzn);
        }

        CalculateDiscountRequest request = CalculateDiscountRequest.builder()
                .subscriptionPlanId(packageId)
                .optionId(optionId)
                .originalPrice(BigDecimal.valueOf(originalAmountAzn).setScale(2, java.math.RoundingMode.HALF_UP))
                .useCoin(true)
                .build();

        CalculateDiscountResponse response = coinWalletService.calculateCheckoutDiscount(userId, request);
        BigDecimal coins = response.getCoin() != null && response.getCoin().getAppliedCoins() != null
                ? response.getCoin().getAppliedCoins()
                : BigDecimal.ZERO;
        double finalAmount = response.getFinalPaymentAmount() != null
                ? response.getFinalPaymentAmount().doubleValue()
                : originalAmountAzn;
        BigDecimal original = response.getOriginalPrice() != null
                ? response.getOriginalPrice()
                : BigDecimal.valueOf(originalAmountAzn);

        return new AppliedCheckout(original, finalAmount, coins);
    }
}
