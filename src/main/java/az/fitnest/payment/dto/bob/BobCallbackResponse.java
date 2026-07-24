package az.fitnest.payment.dto.bob;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SmartVista callback / return URL parametrləri DTO-su.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BobCallbackResponse {

    private String orderId;
    private String orderNumber;
    private String status;
    private String checksum;
}
