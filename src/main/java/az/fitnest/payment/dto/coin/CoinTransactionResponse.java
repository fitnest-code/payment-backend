package az.fitnest.payment.dto.coin;

import az.fitnest.payment.model.enums.CoinTransactionType;
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
public class CoinTransactionResponse {
    private Long id;
    private CoinTransactionType type;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String orderId;
    private LocalDateTime expiryDate;
    private String description;
    private LocalDateTime createdDate;
}
