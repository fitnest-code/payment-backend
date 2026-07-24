package az.fitnest.payment.dto.bob;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Bank of Baku ödəniş başlatma cavabı (Form URL və Order ID).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BobInitiateResponse {

    /**
     * Bank of Baku (SmartVista) tərəfindən verilən unikal Order ID
     */
    private String orderId;

    /**
     * Bizim daxili tranzaksiya ID-miz
     */
    private String transactionId;

    /**
     * İstifadəçinin yönləndiriləcəyi bank ödəniş səhifəsi URL-i
     */
    private String formUrl;

    /**
     * Provider adı (BOB)
     */
    private String provider;

    /**
     * Məbləğ
     */
    private Double amount;

    /**
     * Valyuta
     */
    private String currency;

    /**
     * Xəta kodu (əgər varsa)
     */
    private String errorCode;

    /**
     * Xəta mesajı (əgər varsa)
     */
    private String errorMessage;
}
