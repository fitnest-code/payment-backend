package az.fitnest.payment.dto.abb.bnpl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body sent to ABB POST /bnpl/orders.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BnplAbbSubmitRequest {
    private String fin;
    private String term;
    private String phone;
    private Number price;
    private String productName;
    private String reference;
}
