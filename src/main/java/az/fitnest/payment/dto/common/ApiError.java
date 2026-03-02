package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    String code,
    String message,
    Integer status,
    String path,
    OffsetDateTime timestamp,
    Object details
) {}
