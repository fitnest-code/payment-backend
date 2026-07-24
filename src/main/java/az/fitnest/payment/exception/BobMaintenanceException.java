package az.fitnest.payment.exception;

/**
 * Bank of Baku ödəniş endpoint-ləri texniki işlər rejimində olduqda throw edilir.
 */
public class BobMaintenanceException extends RuntimeException {

    public BobMaintenanceException(String message) {
        super(message);
    }

    public BobMaintenanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
