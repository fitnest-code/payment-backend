package az.fitnest.payment.dto.kapital;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KapitalInitiateResponse {

    /** Kapital order id. */
    private String providerOrderId;

    /** FitNest order/transaction id. */
    private String transactionId;

    /** HPP redirect URL (…/flex?id=…&password=…). */
    private String hppUrl;

    private String provider;

    private Double amount;

    private String currency;

    private Integer installmentMonths;

    private String errorCode;

    private String errorMessage;
}
