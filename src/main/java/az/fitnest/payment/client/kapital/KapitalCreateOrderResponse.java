package az.fitnest.payment.client.kapital;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** POST /order response. {@code password}/{@code secret} are sensitive — never log them. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KapitalCreateOrderResponse {

    private Order order;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Order {
        private String id;
        private String password;
        private String hppUrl;
        private String status;
        private String secret;

        @Override
        public String toString() {
            // Lombok's default would print password/secret into logs.
            return "Order(id=" + id + ", hppUrl=" + hppUrl + ", status=" + status + ")";
        }
    }
}
