package az.fitnest.payment.service.coin;

import az.fitnest.payment.exception.BadRequestException;
import az.fitnest.payment.model.entity.CoinSettings;
import lombok.Builder;
import lombok.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
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
        Long packageId;
        String packageName;
        Integer durationMonths;
    }

    public EarnResult calculateV2(
            BigDecimal eligibleCashAmount,
            Long packageId,
            String packageName,
            Integer durationMonths,
            CoinSettings settings,
            Map<String, BigDecimal> tierMultipliers,
            Map<Integer, BigDecimal> periodMultipliers) {

        if (eligibleCashAmount == null || eligibleCashAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return zeroResult(settings, packageId, packageName, durationMonths, eligibleCashAmount);
        }
        if (packageId == null && (packageName == null || packageName.isBlank())) {
            throw new BadRequestException("Paket müəyyən edilməyib");
        }
        if (durationMonths == null || durationMonths <= 0) {
            throw new BadRequestException("Müddət (durationMonths) müəyyən edilməyib");
        }

        BigDecimal tierMult = resolveTierMultiplier(packageId, packageName, tierMultipliers);
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
                .packageId(packageId)
                .packageName(packageName)
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

    private EarnResult zeroResult(
            CoinSettings settings,
            Long packageId,
            String packageName,
            Integer durationMonths,
            BigDecimal amount) {
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
                .packageId(packageId)
                .packageName(packageName)
                .durationMonths(durationMonths)
                .build();
    }

    /**
     * Multipliers are keyed by packageId ("1", "2", …) from admin settings.
     * Falls back to package name key, then 1.0 if not configured yet.
     */
    public static BigDecimal resolveTierMultiplier(
            Long packageId,
            String packageName,
            Map<String, BigDecimal> tierMultipliers) {
        if (tierMultipliers == null || tierMultipliers.isEmpty()) {
            return BigDecimal.ONE;
        }
        if (packageId != null) {
            BigDecimal byId = tierMultipliers.get(String.valueOf(packageId));
            if (byId != null) {
                return byId;
            }
        }
        if (packageName != null && !packageName.isBlank()) {
            BigDecimal byName = tierMultipliers.get(packageName.trim());
            if (byName != null) {
                return byName;
            }
        }
        return BigDecimal.ONE;
    }

    public static BigDecimal resolvePeriodMultiplier(
            Integer durationMonths,
            Map<Integer, BigDecimal> periodMultipliers) {
        if (durationMonths == null || durationMonths <= 0) {
            throw new BadRequestException("Müddət (durationMonths) müəyyən edilməyib");
        }
        if (periodMultipliers == null || periodMultipliers.isEmpty()) {
            return BigDecimal.ONE;
        }
        BigDecimal mult = periodMultipliers.get(durationMonths);
        return mult != null ? mult : BigDecimal.ONE;
    }

    public static Map<String, BigDecimal> copyTierMap(Map<String, BigDecimal> input) {
        Map<String, BigDecimal> copy = new HashMap<>();
        if (input != null) {
            input.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    copy.put(key.trim(), value);
                }
            });
        }
        return copy;
    }
}
