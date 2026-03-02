package az.fitnest.payment.dto.common;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Builder;

@Builder
public record ErrorResponse(
    String message,
    String code,
    LocalDateTime timestamp,
    String path,
    Map<String, Object> details
) {
    public static ErrorResponse of(String message, String code) {
        return ErrorResponse.builder()
                .message(message)
                .code(code)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ErrorResponse of(String message, String code, String path) {
        return ErrorResponse.builder()
                .message(message)
                .code(code)
                .path(path)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
