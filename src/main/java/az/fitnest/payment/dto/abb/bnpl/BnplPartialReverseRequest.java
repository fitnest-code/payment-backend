package az.fitnest.payment.dto.abb.bnpl;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BnplPartialReverseRequest {

    @NotNull
    @DecimalMin("1.0")
    private Double amount;
}
