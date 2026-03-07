package az.fitnest.payment.dto.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCardResponse {
    private Long id;
    private String cardId;
    private String cardMask;
    private String cardName;
    private String brand;
    private boolean isDefault;
    private Instant createdAt;
    private Instant updatedAt;
}
