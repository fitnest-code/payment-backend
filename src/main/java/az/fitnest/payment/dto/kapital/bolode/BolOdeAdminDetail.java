package az.fitnest.payment.dto.kapital.bolode;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Full admin view; FIN/DOB are needed to relay the application to the bank by hand. */
@Data
@Builder
public class BolOdeAdminDetail {
    private Long id;
    private String displayId;
    private Long paymentId;
    private String paymentReference;
    private Long userId;
    private String finCode;
    private LocalDate birthDate;
    private String contactPhone;
    private String extraPhone1;
    private String extraPhone2;
    private boolean birbankConsentAccepted;
    private boolean mygovConsentAccepted;
    private Long packageId;
    private Long optionId;
    private String packageName;
    private Integer durationMonths;
    private BigDecimal finalAmount;
    private String status;
    private String declineReason;
    private Boolean declineReasonVisibleToCustomer;
    private Long assignedAdminUserId;
    private String internalNote;
    private long waitRemainingSeconds;
    private Instant createdAt;
    private Instant updatedAt;
}
