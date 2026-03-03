package az.fitnest.payment.exception;

import org.springframework.http.HttpStatus;

public class BadRequestException extends BaseException {

    private static final long serialVersionUID = 1L;

    public BadRequestException(String message) {
        super(message, "BAD_REQUEST", HttpStatus.BAD_REQUEST);
    }
}
