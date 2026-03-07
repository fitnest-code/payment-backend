package az.fitnest.payment.dto.common;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetDefaultCardRequest {
    @NotNull(message = "Card ID is required")
    private Long cardId;
}
