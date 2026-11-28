package az.fitnest.payment.dto.kapital.bolode;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Customer-facing application detail. Never exposes internalNote or the assigned admin. */
@Data
@Builder
public class BolOdeApplicationResponse {
    private Long id;
    private String displayId;
    private String status;
    private String paymentReference;
    private String packageName;
    private Integer durationMonths;
    private BigDecimal finalAmount;
    /** Full FIN on own detail (list/summary still use maskedFin). */
    private String finCode;
    private String maskedFin;
    private LocalDate birthDate;
    private String contactPhone;
    private String extraPhone1;
    private String extraPhone2;
    /** Only populated when the admin marked the reason visible to the customer. */
    private String declineReason;
    private boolean cancellable;
    private long waitRemainingSeconds;
    private Instant createdAt;
    private Instant updatedAt;
}
