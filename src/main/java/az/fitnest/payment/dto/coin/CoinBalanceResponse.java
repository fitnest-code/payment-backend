package az.fitnest.payment.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinBalanceResponse {

    private BigDecimal coinBalance;
    private BigDecimal aznEquivalent;
    /** Same meaning as identity {@code is_welcome_bonus_received}: wallet already got the entrance bonus. */
    private Boolean welcomeBonusReceived;
    /** True after the user dismissed the entrance popup (Close / X / Details). */
    private Boolean welcomeBonusPopupShown;
    /** Admin-configured welcome amount; present when received and popup not yet shown. */
    private BigDecimal welcomeBonusAmount;
}
