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
         * Taksit sayı. Etibarlı dəyərlər: 3, 6, 9, 12, 18, 24, 27, 30.
         * null və ya 0 göndərildikdə taksitsiz ödəniş başladılır.
         */
        Integer installmentMonths
) {}
