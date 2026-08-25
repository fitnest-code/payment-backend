package az.fitnest.payment.util;

/**
 * Normalizes card PANs for storage and API responses to last-4 only.
 * Never store or return BIN + last4 bank formats (e.g. {@code 521097******0454}).
 */
public final class CardMaskUtil {

    public static final String EMPTY_DISPLAY = "************";

    private CardMaskUtil() {
    }

    /**
     * Returns {@code ************} + last 4 digits, or the original blank/null value.
     */
    public static String toLast4(String cardMaskOrPan) {
        if (cardMaskOrPan == null || cardMaskOrPan.isBlank()) {
            return cardMaskOrPan;
        }
        String digits = cardMaskOrPan.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return EMPTY_DISPLAY;
        }
        return EMPTY_DISPLAY + digits.substring(digits.length() - 4);
    }

    /** True when the value still contains more than last-4 digits (e.g. BIN present). */
    public static boolean hasMoreThanLast4(String cardMaskOrPan) {
        if (cardMaskOrPan == null || cardMaskOrPan.isBlank()) {
            return false;
        }
        return cardMaskOrPan.replaceAll("[^0-9]", "").length() > 4;
    }
}
