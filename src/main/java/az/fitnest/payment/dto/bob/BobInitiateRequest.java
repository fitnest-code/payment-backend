package az.fitnest.payment.dto.bob;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bank of Baku vasitəsilə ödəniş başlatma sorğusu.
 * V1 endpoints force isCoinUsed=false; V2 honors the flag.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class BobInitiateRequest {

    /**
     * Abunəlik paketi ID-si (seçilən paket)
     */
    @NotNull(message = "Package ID məcburidir")
    private Long packageId;

    /**
     * Paket seçimi ID-si
     */
    private Long optionId;

    /**
     * Kartın növbəti ödənişlər üçün saxlanılması istəyi (Binding)
     */
    @Builder.Default
    private Boolean saveCard = false;

    /**
     * Valyuta (defolt AZN)
     */
    private String currency;

    /**
     * Əlavə təsvir
     */
    private String description;

    /**
     * Taksit ayları (məs: 3, 6, 12). Boş olduqda və ya null/0 olduqda birdəfəlik ödənişdir.
     */
    private Integer installmentMonths;

    /**
     * true olduqda mövcud Coin balansından endirim tətbiq olunur (ödəniş = qiymət − coin AZN).
     * Alias: coinPaymentEnabled
     */
    @Builder.Default
    @com.fasterxml.jackson.annotation.JsonAlias({"coinPaymentEnabled", "coin_payment_enabled", "is_coin_used"})
    private Boolean isCoinUsed = false;
}
