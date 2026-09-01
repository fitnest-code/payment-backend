package az.fitnest.payment.service.coin;

import az.fitnest.payment.exception.BadRequestException;
import az.fitnest.payment.model.entity.CoinSettings;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Component
public class CoinEarnCalculator {

    public static final String FORMULA_V2 = "EARN_V2_20260901";

    @Value
    @Builder
    public static class EarnResult {
        String formulaVersion;
        BigDecimal baseEarnRate;
        BigDecimal tierMultiplier;
        BigDecimal periodMultiplier;
        BigDecimal rawGivebackRate;
        BigDecimal appliedGivebackRate;
        BigDecimal earnCoinFactor;
        BigDecimal eligibleCashAmount;
        BigDecimal rawCoins;
        int awardedCoins;
        String tierName;
        Integer durationMonths;
    }

    public EarnResult calculateV2(
            BigDecimal eligibleCashAmount,
            String tierName,
            Integer durationMonths,
            CoinSettings settings,
            Map<String, BigDecimal> tierMultipliers,
            Map<Integer, BigDecimal> periodMultipliers) {

        if (eligibleCashAmount == null || eligibleCashAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return zeroResult(settings, tierName, durationMonths, eligibleCashAmount);
        }

        String normalizedTier = normalizeTier(tierName);
        BigDecimal tierMult = resolveTierMultiplier(normalizedTier, tierMultipliers);
        BigDecimal periodMult = resolvePeriodMultiplier(durationMonths, periodMultipliers);

        BigDecimal baseEarnRate = settings.getBaseEarnRate() != null
                ? settings.getBaseEarnRate()
                : new BigDecimal("0.020000");
        BigDecimal maxGivebackRate = settings.getMaxGivebackRate() != null
                ? settings.getMaxGivebackRate()
                : new BigDecimal("0.050000");
        BigDecimal earnCoinFactor = settings.getEarnCoinFactor() != null
                ? settings.getEarnCoinFactor()
                : new BigDecimal("10.00");

        BigDecimal rawGivebackRate = baseEarnRate
                .multiply(tierMult)
                .multiply(periodMult);
        BigDecimal appliedGivebackRate = rawGivebackRate.min(maxGivebackRate);

        BigDecimal rawCoins = eligibleCashAmount
                .multiply(appliedGivebackRate)
                .multiply(earnCoinFactor);
        int awardedCoins = rawCoins.setScale(0, RoundingMode.HALF_UP).intValue();

        return EarnResult.builder()
                .formulaVersion(settings.getFormulaVersion() != null ? settings.getFormulaVersion() : FORMULA_V2)
                .baseEarnRate(baseEarnRate)
                .tierMultiplier(tierMult)
                .periodMultiplier(periodMult)
                .rawGivebackRate(rawGivebackRate)
                .appliedGivebackRate(appliedGivebackRate)
                .earnCoinFactor(earnCoinFactor)
                .eligibleCashAmount(eligibleCashAmount.setScale(2, RoundingMode.HALF_UP))
                .rawCoins(rawCoins.setScale(6, RoundingMode.HALF_UP))
                .awardedCoins(awardedCoins)
                .tierName(normalizedTier)
                .durationMonths(durationMonths)
                .build();
    }

    public BigDecimal calculateV1(BigDecimal netPaidAmount, BigDecimal earnRateAznToCoin) {
        if (netPaidAmount == null || netPaidAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = earnRateAznToCoin != null ? earnRateAznToCoin : BigDecimal.ZERO;
        return netPaidAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public boolean isV2Formula(CoinSettings settings) {
        return settings.getFormulaVersion() != null
                && settings.getFormulaVersion().startsWith("EARN_V2");
    }

    private EarnResult zeroResult(CoinSettings settings, String tierName, Integer durationMonths, BigDecimal amount) {
        return EarnResult.builder()
                .formulaVersion(settings.getFormulaVersion())
                .baseEarnRate(settings.getBaseEarnRate())
                .tierMultiplier(BigDecimal.ONE)
                .periodMultiplier(BigDecimal.ONE)
                .rawGivebackRate(BigDecimal.ZERO)
                .appliedGivebackRate(BigDecimal.ZERO)
                .earnCoinFactor(settings.getEarnCoinFactor())
                .eligibleCashAmount(amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                .rawCoins(BigDecimal.ZERO)
                .awardedCoins(0)
                .tierName(normalizeTier(tierName))
                .durationMonths(durationMonths)
                .build();
    }

    private BigDecimal resolveTierMultiplier(String tierName, Map<String, BigDecimal> tierMultipliers) {
        if (tierMultipliers == null || tierMultipliers.isEmpty()) {
            throw new BadRequestException("Tier multiplier konfiqurasiyası tapılmadı: " + tierName);
        }
        BigDecimal mult = tierMultipliers.get(tierName);
        if (mult == null) {
            throw new BadRequestException("Dəstəklənməyən tier: " + tierName);
        }
        return mult;
    }

    private BigDecimal resolvePeriodMultiplier(Integer durationMonths, Map<Integer, BigDecimal> periodMultipliers) {
        if (durationMonths == null) {
            throw new BadRequestException("Müddət (durationMonths) müəyyən edilməyib");
        }
        if (periodMultipliers == null || periodMultipliers.isEmpty()) {
            throw new BadRequestException("Müddət multiplier konfiqurasiyası tapılmadı");
        }
        BigDecimal mult = periodMultipliers.get(durationMonths);
        if (mult == null) {
            throw new BadRequestException("Dəstəklənməyən müddət: " + durationMonths + " ay");
        }
        return mult;
    }

    public static String normalizeTier(String tierName) {
        if (tierName == null || tierName.isBlank()) {
            return "BRONZE";
        }
        return tierName.trim().toUpperCase();
    }
}
