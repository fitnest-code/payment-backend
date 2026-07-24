package az.fitnest.payment.exception;

/**
 * Bank of Baku ödəniş əməliyyatları zamanı baş verən istisnalar üçün istifadə edilir.
 */
public class BobPaymentException extends RuntimeException {

    private final String errorCode;

    public BobPaymentException(String message) {
        super(message);
        this.errorCode = "BOB_PAYMENT_ERROR";
    }

    public BobPaymentException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BobPaymentException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "BOB_PAYMENT_ERROR";
    }

    public BobPaymentException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
