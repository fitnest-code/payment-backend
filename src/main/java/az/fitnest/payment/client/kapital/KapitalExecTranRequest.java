package az.fitnest.payment.client.kapital;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /order/{id}/exec-tran body.
 * Same-day cancel: voidKind Full|Partial (no type). Later: type=Refund with amount.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KapitalExecTranRequest {

    private Tran tran;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tran {
        @Builder.Default
        private String phase = "Single";
        /** "Refund" for post-settlement returns; null for voids. */
        private String type;
        /** "Full" or "Partial" for same-day voids; null for refunds. */
        private String voidKind;
        /** Decimal AZN string; omitted for full voids. */
        private String amount;
    }

    public static KapitalExecTranRequest voidFull() {
        return KapitalExecTranRequest.builder().tran(Tran.builder().voidKind("Full").build()).build();
    }

    public static KapitalExecTranRequest voidPartial(String amount) {
        return KapitalExecTranRequest.builder()
                .tran(Tran.builder().voidKind("Partial").amount(amount).build()).build();
    }

    public static KapitalExecTranRequest refund(String amount) {
        return KapitalExecTranRequest.builder()
                .tran(Tran.builder().type("Refund").amount(amount).build()).build();
    }
}
