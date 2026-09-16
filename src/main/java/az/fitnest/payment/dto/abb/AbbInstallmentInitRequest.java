package az.fitnest.payment.dto.abb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/**
 * V1 ABB init body — pre-coin contract.
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record AbbInstallmentInitRequest(
        Long packageId,
        Long optionId,
        /**
         * Taksit sayı. null/0 → taksitsiz (ACQ_INST_PAYIN=X).
         */
        Integer installmentMonths
) {}
