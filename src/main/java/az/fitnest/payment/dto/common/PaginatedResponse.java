package az.fitnest.payment.dto.common;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import org.springframework.data.domain.Page;
import java.util.List;

@Builder
@Schema(description = "Paginated response wrapper")
public record PaginatedResponse<T>(
        @ArraySchema(schema = @Schema(description = "List of items in the current page", implementation = Object.class))
        List<T> items,

        @Schema(description = "Total number of items available")
        long total,

        @Schema(description = "Current page number")
        int page,

        @Schema(description = "Number of items per page")
        int pageSize,

        @Schema(description = "Optional message for additional context")
        String message
) {
    public static <T> PaginatedResponse<T> of(Page<T> pageResult) {
        int pageNumber = pageResult.getNumber() + 1;
        return PaginatedResponse.<T>builder()
                .items(pageResult.getContent())
                .total(pageResult.getTotalElements())
                .page(pageNumber)
                .pageSize(pageResult.getSize())
                .build();
    }
}
