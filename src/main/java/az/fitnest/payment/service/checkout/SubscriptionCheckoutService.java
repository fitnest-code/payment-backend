package az.fitnest.payment.service.checkout;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.exception.BadRequestException;
import az.fitnest.payment.service.coin.CoinCheckoutHelper;
import az.fitnest.payment.util.PaymentPackageRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Single entry-point for subscription checkout pricing: package/option validation,
 * server-side price resolution, and optional coin discount (DRY across providers).
 *
 * <p>When {@code autoPaymentEnabled} is true, the full available FitNest Coin balance
 * is applied automatically on every charge (initial checkout and renewals).</p>
 */
@Service
@RequiredArgsConstructor
public class SubscriptionCheckoutService {

    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final CoinCheckoutHelper coinCheckoutHelper;

    public record CheckoutQuote(
            Long packageId,
            Long optionId,
            double originalAmountAzn,
            double chargeAmountAzn,
            String currency,
            int durationMonths,
            BigDecimal coinsUsed,
            String packageRefDescription
    ) {
    }

    public CheckoutQuote quote(Long userId, Long packageId, Long optionId, Boolean coinPaymentEnabled) {
        return quote(userId, packageId, optionId, coinPaymentEnabled, false);
    }

    public CheckoutQuote quote(Long userId,
                               Long packageId,
                               Long optionId,
                               Boolean coinPaymentEnabled,
                               Boolean autoPaymentEnabled) {
        if (packageId == null || optionId == null) {
            throw new BadRequestException("packageId and optionId are required");
        }
        if (!subscriptionPackageGrpcClient.checkOptionInPackageExists(packageId, optionId)) {
            throw new BadRequestException("Invalid packageId or optionId");
        }

        var price = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
        if (price == null || price.amount <= 0) {
            throw new BadRequestException("Unable to resolve package price");
        }

        var applied = coinCheckoutHelper.applyForSubscription(
                userId, packageId, optionId, coinPaymentEnabled, autoPaymentEnabled, price.amount);

        String currency = price.currency != null && !price.currency.isBlank() ? price.currency : "AZN";
        return new CheckoutQuote(
                packageId,
                optionId,
                applied.originalPriceAzn().doubleValue(),
                applied.finalAmountAzn(),
                currency,
                price.durationMonths,
                applied.coinsUsed(),
                PaymentPackageRef.encode(packageId, optionId)
        );
    }

    public void requireOneMonthForAutoPay(CheckoutQuote quote, Boolean autoPaymentEnabled) {
        if (Boolean.TRUE.equals(autoPaymentEnabled) && quote.durationMonths() != 1) {
            throw new BadRequestException("Avtomatik ödəniş yalnız 1 aylıq paketlər üçün keçərlidir");
        }
    }
}
