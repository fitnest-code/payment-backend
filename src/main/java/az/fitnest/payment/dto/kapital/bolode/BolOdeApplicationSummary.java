package az.fitnest.payment.dto.kapital.bolode;

import lombok.Builder;
import lombok.Data;

/** Lightweight polling payload for GET /status/{id}. */
@Data
@Builder
public class BolOdeApplicationSummary {
    private Long id;
    private String displayId;
    private String status;
    private String declineReason;
    private long waitRemainingSeconds;
}
