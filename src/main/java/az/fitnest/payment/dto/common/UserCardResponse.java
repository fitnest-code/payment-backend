package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserCardResponse(
    String cardId,
    String cardName,
    String cardMask,
    String brand
) {
    public UserCardResponse(String cardId, String cardName, String cardMask, String brand) {
        this.cardId = cardId;
        this.cardName = cardName;
        this.cardMask = cardMask;
        this.brand = brand;
    }
}
