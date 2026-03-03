package az.fitnest.payment.exception;

import org.springframework.http.HttpStatus;

public class BadRequestException extends BaseException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message, "CONFLICT", HttpStatus.CONFLICT);
    }
}
