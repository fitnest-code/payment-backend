package az.fitnest.payment.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 409: the user already has an active Böl ödə application. Carries ids so the app can deep-link to it. */
@Getter
public class BolOdeActiveApplicationException extends BaseException {

    private static final long serialVersionUID = 1L;

    private final Long applicationId;
    private final String displayId;

    public BolOdeActiveApplicationException(Long applicationId, String displayId) {
        super("Aktiv Böl ödə müraciəti artıq mövcuddur: " + displayId,
                "BOL_ODE_ACTIVE_APPLICATION_EXISTS", HttpStatus.CONFLICT);
        this.applicationId = applicationId;
        this.displayId = displayId;
    }
}
