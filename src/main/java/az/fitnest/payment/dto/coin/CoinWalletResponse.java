package az.fitnest.payment.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinWalletResponse {
    private BigDecimal totalBalance;
    private BigDecimal aznEquivalent;
    private BigDecimal expiringSoonCoins;
    private LocalDateTime nextExpiryDate;
}
