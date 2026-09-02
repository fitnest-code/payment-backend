package az.fitnest.payment.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FullPaymentEligibilityResponse {

    private BigDecimal coinBalance;
    private BigDecimal aznEquivalent;
    private Boolean isEligibleForFullPayment;
}
