package az.fitnest.payment.dto.abb.bnpl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mobile → FitNest: start BNPL credit request.
 * V1 endpoints force isCoinUsed=false and clear coinsToUse; V2 honors coin fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class BnplInitRequest {

    @NotNull(message = "Package ID məcburidir")
    private Long packageId;

    @NotNull(message = "Option ID məcburidir")
    private Long optionId;

    /** Customer ID card FIN (max 7). */
    @NotBlank(message = "FIN məcburidir")
    private String fin;

    /**
     * Phone registered in ABB mobile app.
     * Accepts +994... or local 77... formats.
     */
    @NotBlank(message = "Telefon nömrəsi məcburidir")
    private String phone;

    /**
     * Credit term enum from ABB: 1, 3, 4, 6, 9, 12, 18, 24.
     */
    @NotNull(message = "Kredit müddəti məcburidir")
    private Integer term;

    /** Optional coins to apply before sending net price to ABB. */
    private java.math.BigDecimal coinsToUse;

    /** true: apply maximum available coins (overrides coinsToUse when set). Alias: coinPaymentEnabled */
    @com.fasterxml.jackson.annotation.JsonAlias({"coinPaymentEnabled", "coin_payment_enabled", "is_coin_used"})
    private Boolean isCoinUsed;
}
