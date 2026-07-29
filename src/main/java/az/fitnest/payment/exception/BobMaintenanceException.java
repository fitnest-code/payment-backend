package az.fitnest.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Bank of Baku ödəniş endpoint-ləri texniki işlər rejimində olduqda throw edilir.
 */
public class BobMaintenanceException extends BaseException {

    private static final long serialVersionUID = 1L;

    public BobMaintenanceException(String message) {
        super(message, "NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
