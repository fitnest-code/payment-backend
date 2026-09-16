package az.fitnest.payment.dto.coin;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinTermsAdminRequest {
    @NotNull
    private String htmlContentAz;

    @NotNull
    private String htmlContentEn;

    @NotNull
    private String htmlContentRu;
}
