package az.fitnest.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Kapital Bank payment failures (validation, bank API errors, missing payment).
 */
public class KapitalPaymentException extends BaseException {

    private static final long serialVersionUID = 1L;

    public KapitalPaymentException(String message) {
        super(message, "KAPITAL_PAYMENT_ERROR", HttpStatus.BAD_REQUEST);
    }

    public KapitalPaymentException(String errorCode, String message) {
        super(message, errorCode, HttpStatus.BAD_REQUEST);
    }
}
