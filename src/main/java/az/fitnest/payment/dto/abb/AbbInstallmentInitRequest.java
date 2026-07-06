package az.fitnest.payment.dto.abb;

import lombok.Builder;

/**
 * Frontend-dən ABB taksit ödənişi başlatmaq üçün gələn sorğu.
 */
@Builder
public record AbbInstallmentInitRequest(
        /** Abunəlik paketi ID-si */
        Long packageId,

        /** Paket seçimi ID-si */
        Long optionId,

        /**
         * Taksit sayı.
         * <ul>
         *   <li>Test terminal aktiv dövrlər: 2, 3, 6, 9, 12 ay</li>
         *   <li>Production terminal: bank konfiqurasiyanıza uyğun dövrlər</li>
         * </ul>
         * {@code null} və ya {@code 0} göndərildikdə taksitsiz ödəniş başladılır
         * (ACQ_INST_PAYIN=X).
         */
        Integer installmentMonths
) {}
