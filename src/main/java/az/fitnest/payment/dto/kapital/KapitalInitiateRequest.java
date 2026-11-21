package az.fitnest.payment.dto.kapital;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Kapital HPP payment start. installmentMonths: null/0 = card, 3|6|12 = TAKSIT. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KapitalInitiateRequest {

    @NotNull(message = "Package ID məcburidir")
    private Long packageId;

    private Long optionId;

    private String description;

    private Integer installmentMonths;

    /** v1 endpoint forces false; v2 honours it. */
    @Builder.Default
    @JsonAlias({"coinPaymentEnabled", "coin_payment_enabled", "is_coin_used"})
    private Boolean isCoinUsed = false;
}
