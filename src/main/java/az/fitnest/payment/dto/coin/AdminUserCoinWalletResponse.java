package az.fitnest.payment.dto.coin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
@Schema(description = "Admin üçün istifadəçi Coin cüzdanı")
public record AdminUserCoinWalletResponse(
        @Schema(description = "İstifadəçi ID-si", example = "38")
        Long userId,
        @Schema(description = "Coin balansı", example = "320.00")
        BigDecimal coinBalance,
        @Schema(description = "Coin-in AZN ekvivalenti", example = "32.00")
        BigDecimal aznEquivalent,
        LocalDateTime expiryDate,
        Long daysUntilExpiry
) {
    public static AdminUserCoinWalletResponse from(Long userId, CoinWalletResponse wallet) {
        CoinWalletResponse source = wallet != null ? wallet : CoinWalletResponse.builder().build();
        BigDecimal balance = source.getTotalBalance() != null ? source.getTotalBalance() : BigDecimal.ZERO;
        BigDecimal azn = source.getAznEquivalent() != null ? source.getAznEquivalent() : BigDecimal.ZERO;
        return AdminUserCoinWalletResponse.builder()
                .userId(userId)
                .coinBalance(balance)
                .aznEquivalent(azn)
                .expiryDate(source.getExpiryDate())
                .daysUntilExpiry(source.getDaysUntilExpiry())
                .build();
    }
}
