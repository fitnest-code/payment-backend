package az.fitnest.payment.service.coin;

import az.fitnest.payment.model.entity.CoinSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoinEarnCalculatorTest {

    private final CoinEarnCalculator calculator = new CoinEarnCalculator();

    @Test
    void looksUpMultiplierByPackageId() {
        CoinSettings settings = v2Settings();
        var result = calculator.calculateV2(
                new BigDecimal("140.00"),
                1L,
                "Bronze",
                3,
                settings,
                Map.of("1", new BigDecimal("1.00"), "2", new BigDecimal("1.10")),
                Map.of(1, new BigDecimal("1.00"), 3, new BigDecimal("1.15")));
        assertEquals(32, result.getAwardedCoins());
        assertEquals(1L, result.getPackageId());
        assertEquals("Bronze", result.getPackageName());
        assertEquals(3, result.getDurationMonths());
    }

    @Test
    void usesOnlyConfiguredDurations() {
        CoinSettings settings = v2Settings();
        var result = calculator.calculateV2(
                new BigDecimal("418.00"),
                2L,
                "Silver",
                6,
                settings,
                Map.of("2", new BigDecimal("1.10")),
                Map.of(6, new BigDecimal("1.30")));
        // 418 * 0.02 * 1.10 * 1.30 * 10 = 119.548 -> 120
        assertEquals(120, result.getAwardedCoins());
    }

    @Test
    void missingPackageMultiplierYieldsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(
                CoinEarnCalculator.resolveTierMultiplier(99L, "Unknown", Map.of("1", new BigDecimal("1.20")))));
    }

    @Test
    void missingPeriodMultiplierYieldsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(
                CoinEarnCalculator.resolvePeriodMultiplier(12, Map.of(1, new BigDecimal("1.00")))));
    }

    @Test
    void missingValuesYieldZeroCoins() {
        CoinSettings settings = v2Settings();
        var result = calculator.calculateV2(
                new BigDecimal("140.00"),
                99L,
                "Unknown",
                3,
                settings,
                Map.of("1", new BigDecimal("1.00")),
                Map.of(1, new BigDecimal("1.00")));
        assertEquals(0, result.getAwardedCoins());
    }

    private static CoinSettings v2Settings() {
        CoinSettings settings = new CoinSettings();
        settings.setFormulaVersion(CoinEarnCalculator.FORMULA_V2);
        settings.setBaseEarnRate(new BigDecimal("0.020000"));
        settings.setMaxGivebackRate(new BigDecimal("0.050000"));
        settings.setEarnCoinFactor(new BigDecimal("10.00"));
        return settings;
    }
}
