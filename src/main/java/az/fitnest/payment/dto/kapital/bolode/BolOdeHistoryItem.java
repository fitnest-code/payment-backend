package az.fitnest.payment.dto.kapital.bolode;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class BolOdeHistoryItem {
    private Long id;
    private String actorType;
    private Long actorUserId;
    private String fromStatus;
    private String toStatus;
    private String message;
    private String declineReason;
    private Instant createdAt;
}
