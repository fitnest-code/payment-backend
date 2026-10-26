package az.fitnest.payment.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Aggregate view of the whole coin ledger, powering the admin history dashboard cards.
 * All monetary values are positive magnitudes (spent/expired are already inverted).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinTransactionSummaryResponse {
    private BigDecimal totalEarned;
    private BigDecimal totalSpent;
    private BigDecimal totalExpired;
    private long totalTransactions;
    private long activeWallets;
    private long totalWallets;
}
