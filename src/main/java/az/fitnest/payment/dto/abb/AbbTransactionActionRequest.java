package az.fitnest.payment.dto.abb;

import lombok.Builder;

/**
 * TRTYPE=21 (Completion), TRTYPE=22/24 (Reversal/Refund) sorğuları üçün DTO.
 *
 * <p>Spec §2.1.1 – 2.2.4 əsasında bu sahələr məcburidir:</p>
 * <ul>
 *   <li>TRTYPE=21 (completion): AMOUNT, CURRENCY, ORDER, RRN, INT_REF</li>
 *   <li>TRTYPE=22 (online reversal): AMOUNT, CURRENCY, ORDER, RRN, INT_REF</li>
 *   <li>TRTYPE=24 (offline reversal): AMOUNT, CURRENCY, ORDER, RRN, INT_REF</li>
 * </ul>
 *
 * <h3>MAC sahə sırası (spec §2.2.2/3/4)</h3>
 * {@code AMOUNT → CURRENCY → TERMINAL → TRTYPE → ORDER → RRN → INT_REF}
 */
@Builder
public record AbbTransactionActionRequest(
        /**
         * Orijinal əməliyyatın ORDER dəyəri.
         * Callback-dən alınmış {@code order} sahəsinin dəyəri.
         */
        String orderId,

        /**
         * Məbləğ. Tam reversal üçün original məbləğ,
         * qismən reversal üçün az məbləğ göndərilə bilər.
         */
        Double amount,

        /** Valyuta kodu. Məs: "AZN" */
        String currency,

        /**
         * Müştəri bankının axtarış istinad nömrəsi (callback-dən RRN sahəsi).
         * ISO-8583 Field 37.
         */
        String rrn,

        /**
         * Gateway daxili istinad nömrəsi (callback-dən INT_REF sahəsi).
         */
        String intRef
) {}
