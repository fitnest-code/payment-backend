package az.fitnest.payment.dto.kapital.bolode;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class BolOdeInitResponse {
    private Long id;
    private String displayId;
    private String status;
    private String paymentReference;
    private BigDecimal finalAmount;
    private Integer durationMonths;
    private Instant createdAt;
    /** Seconds left of the 15-minute response SLA (0 once exceeded). */
    private long waitRemainingSeconds;
}
