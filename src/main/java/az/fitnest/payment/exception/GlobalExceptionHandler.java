package az.fitnest.payment.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import az.fitnest.payment.dto.common.ApiError;
import java.time.OffsetDateTime;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleBaseException(BaseException exception, WebRequest request) {

        Map<String, Object> details = null;
        if (exception instanceof ValidationException validationException) {
            BindingResult result = validationException.getBindingResult();
            if (result != null) {
                details = new HashMap<>();
                Map<String, String> validationErrors = new HashMap<>();
                for (FieldError error : result.getFieldErrors()) {
                    validationErrors.put(error.getField(), safeMessage(error.getDefaultMessage()));
                }
                details.put("fieldIssues", validationErrors);
            }
        }

        ApiError apiError = ApiError.builder()
                .code(exception.getErrorCode())
                .message(getLocalizedMessage(exception.getErrorCode(), exception.getMessage()))
                .status(exception.getHttpStatus().value())
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(OffsetDateTime.now())
                .details(details)
                .build();

        return ResponseEntity.status(exception.getHttpStatus()).body(ApiResponse.error(apiError));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception, WebRequest request) {
        BindingResult result = exception.getBindingResult();
        Map<String, String> validationErrors = new HashMap<>();
        for (FieldError error : result.getFieldErrors()) {
            validationErrors.put(error.getField(), safeMessage(error.getDefaultMessage()));
        }

        ApiError apiError = ApiError.builder()
                .code("VALIDATION_ERROR")
                .message(getMessage("error.validation"))
                .status(HttpStatus.BAD_REQUEST.value())
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(OffsetDateTime.now())
                .details(Map.of("fieldIssues", validationErrors))
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(apiError));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException exception, WebRequest request) {
        ApiError apiError = ApiError.builder()
                .code("HTTP_MESSAGE_NOT_READABLE")
                .message(getMessage("error.invalid_json_format"))
                .status(HttpStatus.BAD_REQUEST.value())
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(OffsetDateTime.now())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(apiError));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(RuntimeException ex, WebRequest request) {
        ApiError apiError = ApiError.builder()
                .code("RUNTIME_EXCEPTION")
                .message(getMessage("error.unexpected"))
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(OffsetDateTime.now())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(apiError));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex, WebRequest request) {
        ApiError apiError = ApiError.builder()
                .code("INTERNAL_SERVER_ERROR")
                .message(getMessage("error.internal_server_error"))
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .path(request.getDescription(false).replace("uri=", ""))
                .timestamp(OffsetDateTime.now())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(apiError));
    }

    private String getLocalizedMessage(String errorCode, String defaultMessage) {
        String key = "error." + errorCode.toLowerCase();
        String message = getMessage(key);
        if (message.equals(key)) {
            // Try resolving by original errorCode
            message = getMessage(errorCode);
            if (message.equals(errorCode)) {
                return safeMessage(defaultMessage);
            }
        }
        return message;
    }

    private String safeMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return getMessage("error.unexpected");
        }
        // If the message looks like a key, try to resolve it
        if (msg.startsWith("error.")) {
            String resolved = getMessage(msg);
            if (!resolved.equals(msg)) {
                return resolved;
            }
        }
        return msg;
    }

    private String getMessage(String code) {
        try {
            return messageSource.getMessage(code, null, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return code; // Fallback to code if message not found
        }
    }
}
