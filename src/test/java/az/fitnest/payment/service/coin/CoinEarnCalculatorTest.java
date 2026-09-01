package az.fitnest.payment.service.coin;

import az.fitnest.payment.model.entity.CoinSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CoinEarnCalculatorTest {

    private final CoinEarnCalculator calculator = new CoinEarnCalculator();

    @Test
    void mapsEnglishPackageNames() {
        assertEquals("BRONZE", CoinEarnCalculator.normalizeTier("Bronze"));
        assertEquals("SILVER", CoinEarnCalculator.normalizeTier("Silver"));
        assertEquals("GOLD", CoinEarnCalculator.normalizeTier("Gold"));
        assertEquals("PLATINUM", CoinEarnCalculator.normalizeTier("Platinum"));
    }

    @Test
    void mapsLocalizedAndMessyNames() {
        assertEquals("BRONZE", CoinEarnCalculator.normalizeTier("  Bürünc  "));
        assertEquals("SILVER", CoinEarnCalculator.normalizeTier("Gümüş"));
        assertEquals("GOLD", CoinEarnCalculator.normalizeTier("Qızıl"));
        assertEquals("PLATINUM", CoinEarnCalculator.normalizeTier("Platin"));
        assertEquals("BRONZE", CoinEarnCalculator.normalizeTier("Бронза"));
        assertEquals("SILVER", CoinEarnCalculator.normalizeTier("Серебро"));
    }

    @Test
    void doesNotCorruptSilverOnTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("az"));
            assertEquals("SILVER", CoinEarnCalculator.normalizeTier("Silver"));
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertEquals("SILVER", CoinEarnCalculator.normalizeTier("Silver"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void bronze3Month140AznAwards32Coins() {
        CoinSettings settings = v2Settings();
        var result = calculator.calculateV2(
                new BigDecimal("140.00"),
                "Bronze",
                3,
                settings,
                settings.getTierMultipliers(),
                settings.getPeriodMultipliers());
        assertEquals(32, result.getAwardedCoins());
        assertEquals("BRONZE", result.getTierName());
        assertEquals(3, result.getDurationMonths());
    }

    @Test
    void platinumAnnual2160AznAwards842Coins() {
        CoinSettings settings = v2Settings();
        var result = calculator.calculateV2(
                new BigDecimal("2160.00"),
                "Platinum",
                12,
                settings,
                settings.getTierMultipliers(),
                settings.getPeriodMultipliers());
        assertEquals(842, result.getAwardedCoins());
    }

    @Test
    void unknownNameDoesNotSilentlyBecomeBronze() {
        assertNull(CoinEarnCalculator.normalizeTier("Light"));
        assertNull(CoinEarnCalculator.normalizeTier(" "));
    }

    private static CoinSettings v2Settings() {
        CoinSettings settings = new CoinSettings();
        settings.setFormulaVersion(CoinEarnCalculator.FORMULA_V2);
        settings.setBaseEarnRate(new BigDecimal("0.020000"));
        settings.setMaxGivebackRate(new BigDecimal("0.050000"));
        settings.setEarnCoinFactor(new BigDecimal("10.00"));
        settings.setTierMultipliers(Map.of(
                "BRONZE", new BigDecimal("1.00"),
                "SILVER", new BigDecimal("1.10"),
                "GOLD", new BigDecimal("1.20"),
                "PLATINUM", new BigDecimal("1.30")));
        settings.setPeriodMultipliers(Map.of(
                1, new BigDecimal("1.00"),
                3, new BigDecimal("1.15"),
                6, new BigDecimal("1.30"),
                12, new BigDecimal("1.50")));
        return settings;
    }
}
