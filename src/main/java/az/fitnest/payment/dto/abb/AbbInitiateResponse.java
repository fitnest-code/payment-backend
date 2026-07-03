package az.fitnest.payment.dto.abb;

import lombok.Builder;

/**
 * ABB ödənişi başlatma əməliyyatının nəticəsi.
 *
 * <p>Bu DTO-nun məqsədi frontend-ə vahid, provider-agnostic cavab göndərməkdir.
 * Azericard inteqrasiyasında {@code redirectUrl} istifadəçinin
 * bank ödəniş səhifəsinə yönləndirilməsi üçün istifadə olunur.</p>
 */
@Builder
public record AbbInitiateResponse(
        /** Əməliyyat statusu: "success" | "error" */
        String status,

        /**
         * Uğurlu sorğuda: istifadəçinin yönləndiriləcəyi Azericard
         * ödəniş səhifəsinin URL-i (GET forması vasitəsilə).
         */
        String redirectUrl,

        /** Merchant sifariş ID-si (ORDER sahəsi). */
        String orderId,

        /** Xəta olduqda izahat mesajı. */
        String message
) {
    /**
     * Uğurlu nəticə — yönləndirmə URL-i daxildir.
     */
    public static AbbInitiateResponse success(String redirectUrl, String orderId) {
        return AbbInitiateResponse.builder()
                .status("success")
                .redirectUrl(redirectUrl)
                .orderId(orderId)
                .build();
    }

    /**
     * Xəta nəticəsi.
     */
    public static AbbInitiateResponse error(String message) {
        return AbbInitiateResponse.builder()
                .status("error")
                .message(message)
                .build();
    }
}
