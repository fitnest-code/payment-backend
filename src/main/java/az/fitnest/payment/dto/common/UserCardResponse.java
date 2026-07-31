package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserCardResponse(
    String cardId,
    String cardName,
    String cardMask,
    String brand,
    String bank
) {
    public UserCardResponse(String cardId, String cardName, String cardMask, String brand) {
        this(cardId, cardName, cardMask, brand, null);
    }

    public UserCardResponse(String cardId, String cardName, String cardMask, String brand, String bank) {
        this.cardId = cardId;
        this.cardName = cardName;
        this.cardMask = cardMask;
        this.brand = brand;
        this.bank = bank;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("cardBrand")
    public String cardBrand() {
        return brand;
    }
}
