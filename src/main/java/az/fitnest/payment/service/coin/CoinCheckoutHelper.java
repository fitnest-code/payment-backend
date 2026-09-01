package az.fitnest.payment.service.coin;

import az.fitnest.payment.dto.coin.CalculateDiscountRequest;
import az.fitnest.payment.dto.coin.CalculateDiscountResponse;
import az.fitnest.payment.service.CoinWalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Applies FitNest Coin discount for subscription checkout when {@code isCoinUsed} is true.
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
            Boolean isCoinUsed,
            double originalAmountAzn) {
        if (userId == null || !Boolean.TRUE.equals(isCoinUsed)) {
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
