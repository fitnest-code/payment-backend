package az.fitnest.payment.dto.kapital.bolode;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * Mobile → FitNest: submit a Kapital "Böl ödə" application.
 * packageId/optionId mirror the BNPL/Kapital init so pricing goes through the same
 * SubscriptionCheckoutService quote; the price is never taken from the client.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BolOdeInitRequest {

    @NotNull(message = "Package ID məcburidir")
    private Long packageId;

    @NotNull(message = "Option ID məcburidir")
    private Long optionId;

    /** Optional sanity check against the quoted option duration; mismatch → 400. */
    private Integer durationMonths;

    /** Display-only (the quote carries no package name). */
    private String packageName;

    private String finCode;

    private LocalDate birthDate;

    private String contactPhone;
    private String extraPhone1;
    private String extraPhone2;

    private Boolean birbankConsentAccepted;
    private Boolean mygovConsentAccepted;
}
