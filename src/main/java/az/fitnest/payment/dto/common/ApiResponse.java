package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    T data,
    ApiError error
) {
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> error(ApiError apiError) {
        return ApiResponse.<T>builder()
                .error(apiError)
                .build();
    }

    @JsonValue
    public Object asJson() {
        if (error != null) {
            return Map.of("error", error);
        }
        return data;
    }
}
