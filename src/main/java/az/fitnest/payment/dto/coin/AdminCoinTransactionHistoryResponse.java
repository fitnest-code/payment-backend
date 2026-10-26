package az.fitnest.payment.dto.coin;

import az.fitnest.payment.model.enums.CoinRefundAction;
import az.fitnest.payment.model.enums.CoinTransactionSourceType;
import az.fitnest.payment.model.enums.CoinTransactionType;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Versioned admin-ledger row.  It intentionally does not change the mobile
 * transaction response contract; user display data is resolved only here.
 */
@Builder
public record AdminCoinTransactionHistoryResponse(
        Long id,
        Long userId,
        String userName,
        String userPhone,
        CoinTransactionType type,
        CoinRefundAction refundAction,
        CoinTransactionSourceType sourceType,
        String sourceTitle,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String orderId,
        LocalDateTime createdDate
) {}
