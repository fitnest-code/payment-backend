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
     * Optional CVV/CVC. Required by Bank of Baku unless merchant has
     * "Can pay by binding without CVV2/CVC2". When absent, register.do is called
     * with bindingId and the payer enters CVC on the bank formUrl.
     */
    private String cvc;

    /**
     * Abunəlik paketi ID-si
     */
    @NotNull(message = "Package ID məcburidir")
    private Long packageId;

    /**
     * Paket seçimi ID-si
     */
    private Long optionId;
}
