package az.fitnest.payment.dto.kapital;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Kapital Taksit chip options for a catalog package option.
 * {@code installmentMonths} is the bank-configured set filtered to {@code <= durationMonths}.
 */
@Data
@Builder
public class KapitalInstallmentOptionsResponse {
    private Long packageId;
    private Long optionId;
    /** Catalog option duration resolved from order-backend. */
    private int durationMonths;
    /** Eligible TAKSIT months for this option (e.g. [3, 6] when duration is 6). */
    private List<Integer> installmentMonths;
}
