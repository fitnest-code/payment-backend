package az.fitnest.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Bank of Baku ödəniş əməliyyatları zamanı baş verən istisnalar üçün istifadə edilir.
 */
public class BobPaymentException extends BaseException {

    private static final long serialVersionUID = 1L;

    public BobPaymentException(String message) {
        super(message, "BOB_PAYMENT_ERROR", HttpStatus.BAD_REQUEST);
    }

    public BobPaymentException(String errorCode, String message) {
        super(message, errorCode, HttpStatus.BAD_REQUEST);
    }

    public BobPaymentException(String message, Throwable cause) {
        super(message, "BOB_PAYMENT_ERROR", HttpStatus.BAD_REQUEST);
    }

    public BobPaymentException(String errorCode, String message, Throwable cause) {
        super(message, errorCode, HttpStatus.BAD_REQUEST);
    }
}
