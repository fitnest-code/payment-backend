package az.fitnest.payment.dto.kapital.bolode;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class BolOdeAdminListItem {
    private Long id;
    private String displayId;
    private Long userId;
    private String maskedFin;
    private String contactPhone;
    private String packageName;
    private Integer durationMonths;
    private BigDecimal finalAmount;
    private String status;
    private Long assignedAdminUserId;
    private long waitRemainingSeconds;
    private Instant createdAt;
    private Instant updatedAt;
}
