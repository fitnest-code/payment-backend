package az.fitnest.payment.exception;

import org.springframework.http.HttpStatus;

public class BnplPaymentException extends BaseException {

    private static final long serialVersionUID = 1L;

    public BnplPaymentException(String message) {
        super(message, "BNPL_PAYMENT_ERROR", HttpStatus.BAD_REQUEST);
    }

    public BnplPaymentException(String errorCode, String message) {
        super(message, errorCode, HttpStatus.BAD_REQUEST);
    }

    public BnplPaymentException(String errorCode, String message, Throwable cause) {
        super(message, errorCode, HttpStatus.BAD_REQUEST);
        initCause(cause);
    }
}
