package az.fitnest.payment.dto.bob;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Yadda saxlanılmış kartla (Binding) ödəniş etmək üçün sorğu DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BobPayWithSavedCardRequest {

    /**
     * Yadda saxlanılmış kart ID-si (Binding ID / Card ID)
     */
    @NotBlank(message = "Card ID / Binding ID məcburidir")
    private String cardId;

    /**
     * Abunəlik paketi ID-si
     */
    @NotNull(message = "Package ID məcburidir")
    private Long packageId;

    /**
     * Paket seçimi ID-si
     */
    private Long optionId;

    /**
     * FitNest Coin balansından endirim istifadə et (ödəniş = qiymət − coin AZN).
     * Alias: coinPaymentEnabled
     */
    @Builder.Default
    @com.fasterxml.jackson.annotation.JsonAlias({"coinPaymentEnabled", "coin_payment_enabled", "is_coin_used"})
    private Boolean isCoinUsed = false;
}
