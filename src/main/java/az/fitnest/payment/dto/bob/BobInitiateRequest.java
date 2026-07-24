package az.fitnest.payment.dto.bob;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bank of Baku vasitəsilə ödəniş başlatma sorğusu.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
}
