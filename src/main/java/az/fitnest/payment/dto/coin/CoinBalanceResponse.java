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
    /** True when a welcome bonus identifier exists for this user (coins were awarded). */
    private Boolean welcomeBonusAwarded;
    /** True when awarded and the entrance popup has not been dismissed yet. */
    private Boolean showWelcomeBonusPopup;
    /** Configured welcome amount when {@link #showWelcomeBonusPopup} is true; otherwise null. */
    private BigDecimal welcomeBonusAmount;
}
