package az.fitnest.payment.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserCardResponse(
    String cardId,
    String cardName,
    String cardMask,
    String brand,
    String reccPmntExpiry
) {
    public UserCardResponse(String cardId, String cardName, String cardMask, String brand, String reccPmntExpiry) {
        this.cardId = cardId;
        this.cardName = cardName;
        this.cardMask = cardMask;
        this.brand = brand;
        this.reccPmntExpiry = formatExpiry(reccPmntExpiry);
    }

    private static String formatExpiry(String expiry) {
        if (expiry == null || expiry.length() != 4) return expiry;
        return expiry.substring(0, 2) + "/" + expiry.substring(2);
    }
}
