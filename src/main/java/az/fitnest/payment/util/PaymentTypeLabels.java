package az.fitnest.payment.util;

/**
 * Localized payment type labels shared by history and provider status endpoints.
 */
public final class PaymentTypeLabels {

    private PaymentTypeLabels() {
    }

    public static String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return "AZ";
        }
        String upper = lang.trim().toUpperCase();
        if (upper.startsWith("EN")) {
            return "EN";
        }
        if (upper.startsWith("RU")) {
            return "RU";
        }
        if (upper.startsWith("AZ")) {
            return "AZ";
        }
        return "AZ";
    }

    /**
     * Maps persisted payment.type to a stable raw key used for translation.
     */
    public static String rawTypeKey(String paymentType, Boolean autoPaymentEnabled) {
        if (paymentType != null) {
            String t = paymentType.toUpperCase();
            if (t.contains("BNPL")) {
                return "BNPL";
            }
            if (t.contains("INSTALLMENT")) {
                return "INSTALLMENT";
            }
            if ("CARD_BIND".equals(t)) {
                return "CARD_BIND";
            }
            if ("SAVED_CARD".equals(t)) {
                return "SAVED_CARD";
            }
            if ("AUTO_RENEWAL".equals(t)) {
                return "AUTO_RENEWAL";
            }
            if ("GOOGLE_PAY".equals(t)) {
                return "GOOGLE_PAY";
            }
            if ("APPLE_PAY".equals(t)) {
                return "APPLE_PAY";
            }
            if ("WIDGET_PAYMENT".equals(t)) {
                return "WIDGET_PAYMENT";
            }
        }
        if (Boolean.TRUE.equals(autoPaymentEnabled) && paymentType != null
                && paymentType.toUpperCase().contains("AUTO")) {
            return "AUTO_RENEWAL";
        }
        return "ONE_TIME";
    }

    public static String translate(String rawTypeKey, String lang) {
        String l = normalizeLang(lang);
        String key = rawTypeKey != null ? rawTypeKey.toUpperCase().trim() : "ONE_TIME";

        return switch (key) {
            case "AUTO_RENEWAL" -> switch (l) {
                case "RU" -> "Автопродление";
                case "EN" -> "Auto renewal";
                default -> "Avtomatik uzadılma";
            };
            case "ONE_TIME", "BOB_PAYMENT", "ABB_PAYMENT", "PAYMENT" -> switch (l) {
                case "RU" -> "Разовый";
                case "EN" -> "One-time";
                default -> "Birdəfəlik";
            };
            case "WIDGET_PAYMENT" -> switch (l) {
                case "RU" -> "Оплата виджетом";
                case "EN" -> "Widget Payment";
                default -> "Vidcet ödənişi";
            };
            case "APPLE_PAY" -> "Apple Pay";
            case "GOOGLE_PAY" -> "Google Pay";
            case "CARD_BIND" -> switch (l) {
                case "RU" -> "Привязка карты";
                case "EN" -> "Card Binding";
                default -> "Kartın bağlanması";
            };
            case "SAVED_CARD" -> switch (l) {
                case "RU" -> "Сохраненная карта";
                case "EN" -> "Saved Card";
                default -> "Yadda saxlanılmış kart";
            };
            case "INSTALLMENT", "BOB_INSTALLMENT", "ABB_INSTALLMENT" -> switch (l) {
                case "RU" -> "Рассрочка";
                case "EN" -> "Installment";
                default -> "Taksitli ödəniş";
            };
            case "BNPL", "ABB_BNPL" -> switch (l) {
                case "RU" -> "Рассрочка ABB (BNPL)";
                case "EN" -> "ABB Pay Later";
                default -> "ABB ilə hissə-hissə ödə";
            };
            default -> humanize(key);
        };
    }

    public static String fromPayment(String paymentType, Boolean autoPaymentEnabled, String lang) {
        return translate(rawTypeKey(paymentType, autoPaymentEnabled), lang);
    }

    private static String humanize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String formatted = text.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(formatted.charAt(0)) + formatted.substring(1);
    }
}
