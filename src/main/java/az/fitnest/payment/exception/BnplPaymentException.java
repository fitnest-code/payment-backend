package az.fitnest.payment.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * ABB BNPL failure. Carries enough information for the controller to answer the mobile
 * client correctly instead of collapsing every case into a generic 400:
 * duplicate submits become {@code 409}, ambiguous submits stay retryable/pollable.
 */
@Getter
public class BnplPaymentException extends BaseException {

    private static final long serialVersionUID = 1L;

    /** True when the same call may be retried later without side effects. */
    private final boolean retryable;

    /** Raw ABB detail (error detail text) — never shown to the user verbatim. */
    private final String bankDetail;

    public BnplPaymentException(String message) {
        this("BNPL_PAYMENT_ERROR", message, HttpStatus.BAD_REQUEST, false, null);
    }

    public BnplPaymentException(String errorCode, String message) {
        this(errorCode, message, HttpStatus.BAD_REQUEST, false, null);
    }

    public BnplPaymentException(String errorCode, String message, Throwable cause) {
        this(errorCode, message, HttpStatus.BAD_REQUEST, false, null);
        initCause(cause);
    }

    public BnplPaymentException(String errorCode,
                                String message,
                                HttpStatus httpStatus,
                                boolean retryable,
                                String bankDetail) {
        super(message, errorCode, httpStatus);
        this.retryable = retryable;
        this.bankDetail = bankDetail;
    }

    public static BnplPaymentException duplicate(String bankDetail) {
        return new BnplPaymentException("BNPL_ORDER_ALREADY_EXISTS",
                "Hazırda aktiv BNPL sorğunuz mövcuddur. Bir neçə dəqiqə sonra yenidən yoxlayın.",
                HttpStatus.CONFLICT, true, bankDetail);
    }

    public static BnplPaymentException submitUnknown(String bankDetail) {
        return new BnplPaymentException("BNPL_SUBMIT_UNKNOWN",
                "Sifarişin nəticəsi hələ dəqiqləşməyib. Bir az sonra statusu yenidən yoxlayın.",
                HttpStatus.ACCEPTED, true, bankDetail);
    }
}
