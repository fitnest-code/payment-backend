package az.fitnest.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when ABB BNPL endpoints are in maintenance mode.
 */
public class BnplMaintenanceException extends BaseException {

    private static final long serialVersionUID = 1L;

    public BnplMaintenanceException(String message) {
        super(message, "NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
