package az.fitnest.payment.dto.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "İstifadəçinin ödəniş tarixçəsi (Admin üçün)")
public record AdminUserPaymentHistoryResponse(
    @Schema(description = "Tranzaksiya ID-si", example = "TRANS-12345")
    String transactionId,

    @Schema(description = "Tarix və saat", example = "25.10.2023 14:30")
    String dateTime,

    @Schema(description = "Məbləğ", example = "15.50 AZN")
    String amount,

    @Schema(description = "Ödəniş üsulu", example = "Visa")
    String paymentMethod,

    @Schema(description = "Status", example = "Uğurlu")
    String status
) {}
