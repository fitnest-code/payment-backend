package az.fitnest.payment.util;

public class CardBrandDetector {

    public static String detectBrand(String cardMask) {
        if (cardMask == null || cardMask.isEmpty()) {
            return "UNKNOWN";
        }

        // Most cards use the first digit/digits (BIN) to identify the brand
        // Visa: 4
        // Mastercard: 51-55 or 2221-2720
        // American Express: 34, 37
        // Discovery: 6011, 644, 65
        
        String cleanMask = cardMask.replaceAll("[^0-9]", "");
        
        if (cleanMask.startsWith("4")) {
            return "VISA";
        } else if (cleanMask.matches("^(5[1-5]|222[1-9]|22[3-9][0-9]|2[3-6][0-9]{2}|27[01][0-9]|2720).*")) {
            return "MASTERCARD";
        } else if (cleanMask.startsWith("34") || cleanMask.startsWith("37")) {
            return "AMEX";
        } else if (cleanMask.startsWith("6011") || cleanMask.startsWith("644") || cleanMask.startsWith("65")) {
            return "DISCOVER";
        }

        return "UNKNOWN";
    }
}
