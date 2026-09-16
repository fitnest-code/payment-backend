package az.fitnest.payment.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinTermsAdminResponse {
    private String htmlContentAz;
    private String htmlContentEn;
    private String htmlContentRu;
}
