package az.fitnest.payment.client.kapital;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /order body: {"order": {...}}. Amount is a decimal AZN string ("1.00"), not qepik. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KapitalCreateOrderRequest {

    private Order order;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Order {
        /** Single-phase sale. */
        @Builder.Default
        private String typeRid = "Order_SMS";
        private String amount;
        private String currency;
        private String language;
        private String title;
        /** Carries TAKSIT=N for installments; empty/plain text for card. */
        private String description;
        private String hppRedirectUrl;
    }
}
