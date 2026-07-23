package az.fitnest.payment.dto.abb;

import lombok.Builder;

/**
 * ABB tranzaksiya əməliyyatlarının (completion, reversal, status) cavabı.
 */
@Builder
public record AbbTransactionActionResponse(
        /** "success" | "error" */
        String status,

        /** ACTION kodu (bank-dan gəlir): "0"=uğurlu, "2"=rədd */
        String action,

        /** ISO-8583 response code. "00" = approved */
        String rc,

        /** Müştəri bankının təsdiq kodu */
        String approval,

        /** Axtarış istinad nömrəsi */
        String rrn,

        /** Gateway daxili istinad nömrəsi */
        String intRef,

        /** Xəta izahatı */
        String message,

        /** Maskalanmış kart nömrəsi (məs: ************0724) */
        String card,

        // Yastı ödəniş detalları:
        Long paymentId,
        Double amount,
        String currency,
        String occurredAt,
        String cardBrand,
        String type,
        String owner,
        String description
) {
    public static AbbTransactionActionResponse success(String action, String rc,
                                                        String approval, String rrn, String intRef) {
        return AbbTransactionActionResponse.builder()
                .status("success").action(action).rc(rc)
                .approval(approval).rrn(rrn).intRef(intRef)
                .build();
    }

    public static AbbTransactionActionResponse success(String action, String rc,
                                                        String approval, String rrn, String intRef, String card) {
        return AbbTransactionActionResponse.builder()
                .status("success").action(action).rc(rc)
                .approval(approval).rrn(rrn).intRef(intRef).card(card)
                .build();
    }

    public static AbbTransactionActionResponse success(String action, String rc,
                                                        String approval, String rrn, String intRef, String card,
                                                        Long paymentId, Double amount, String currency, String occurredAt,
                                                        String cardBrand, String type, String owner, String description) {
        return AbbTransactionActionResponse.builder()
                .status("success").action(action).rc(rc)
                .approval(approval).rrn(rrn).intRef(intRef).card(card)
                .paymentId(paymentId)
                .amount(amount)
                .currency(currency)
                .occurredAt(occurredAt)
                .cardBrand(cardBrand)
                .type(type)
                .owner(owner)
                .description(description)
                .build();
    }

    public static AbbTransactionActionResponse error(String message) {
        return AbbTransactionActionResponse.builder()
                .status("error").message(message)
                .build();
    }
}
