package az.fitnest.payment.service.kapital;

import az.fitnest.payment.model.enums.KapitalPaymentStatus;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Maps Kapital order status strings to {@link KapitalPaymentStatus}.
 * Statuses are normalised (case, spaces, underscores) because docs and responses disagree
 * ("FullyPaid" vs "Fully paid").
 */
@Component
public class KapitalStatusMapper {

    public KapitalPaymentStatus map(String bankStatus) {
        String key = normalize(bankStatus);
        return switch (key) {
            case "fullypaid" -> KapitalPaymentStatus.SUCCESS;
            case "preparing", "authorized" -> KapitalPaymentStatus.IN_PROGRESS;
            case "partiallypaid" -> KapitalPaymentStatus.PARTIALLY_PAID;
            case "declined", "refused", "rejected", "cancelled", "canceled", "expired" -> KapitalPaymentStatus.FAILED;
            case "refunded", "voided" -> KapitalPaymentStatus.REFUNDED;
            default -> KapitalPaymentStatus.UNKNOWN;
        };
    }

    static String normalize(String status) {
        if (status == null) {
            return "";
        }
        return status.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
    }
}
