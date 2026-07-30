package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserCardResponse(
    String cardId,
    String cardName,
    String cardMask,
    String brand,
    String bank,
    String logoUrl
) {
    public UserCardResponse(String cardId, String cardName, String cardMask, String brand) {
        this(cardId, cardName, cardMask, brand, null, null);
    }

    public UserCardResponse(String cardId, String cardName, String cardMask, String brand, String logoUrl) {
        this(cardId, cardName, cardMask, brand, null, logoUrl);
    }

    public UserCardResponse(String cardId, String cardName, String cardMask, String brand, String bank, String logoUrl) {
        this.cardId = cardId;
        this.cardName = cardName;
        this.cardMask = cardMask;
        this.brand = brand;
        this.bank = bank;
        this.logoUrl = logoUrl;
    }
}
