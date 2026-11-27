package az.fitnest.payment.dto.kapital.bolode;

import az.fitnest.payment.model.enums.BolOdeApplicationStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * PATCH body. Only these fields are editable; null = leave unchanged.
 * Unknown properties are ignored so clients cannot smuggle in FIN/DOB edits.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BolOdeAdminUpdateRequest {
    private BolOdeApplicationStatus status;
    private Long assignedAdminUserId;
    private String declineReason;
    private Boolean declineReasonVisibleToCustomer;
    private String internalNote;
}
