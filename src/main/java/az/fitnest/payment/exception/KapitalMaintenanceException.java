package az.fitnest.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when Kapital endpoints are disabled via kapital.maintenance-mode.
 * 404 (like BOB) so clients treat the provider as unavailable rather than as a server fault.
 */
public class KapitalMaintenanceException extends BaseException {

    private static final long serialVersionUID = 1L;

    public KapitalMaintenanceException(String message) {
        super(message, "NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
