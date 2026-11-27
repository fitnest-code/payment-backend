package az.fitnest.payment.dto.kapital;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Refund/void request. Identify the payment by FitNest orderId or Kapital providerOrderId. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KapitalRefundRequest {

    private String orderId;

    private String providerOrderId;

    /** AZN. Null or >= payment amount = full cancel. */
    private Double amount;
}
