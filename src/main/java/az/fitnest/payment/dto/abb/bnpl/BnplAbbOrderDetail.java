package az.fitnest.payment.dto.abb.bnpl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ABB Get Order Detail response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BnplAbbOrderDetail {
    private Long orderId;
    private String term;
    private String merchantId;
    private String merchantName;
    private String partnerCif;
    private String partnerName;
    private String productName;
    private Number price;
    private String firstName;
    private String lastName;
    private String status;
    private Number partnerCommission;
    private String createdDate;
    private String reference;
}
