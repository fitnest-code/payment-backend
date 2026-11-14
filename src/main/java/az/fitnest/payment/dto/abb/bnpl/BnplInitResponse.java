package az.fitnest.payment.dto.abb.bnpl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BnplInitResponse {

    private String status;
    private String reference;
    private String abbOrderId;
    private String abbStatus;
    private String message;
    private Double amount;
    private String currency;
    private Integer term;
    private String errorCode;
    private String errorMessage;

    /**
     * True when the same call may be retried later (duplicate / unknown submit).
     */
    private Boolean retryable;

    /**
     * True when the response describes an order that already existed locally instead of
     * a freshly created one (idempotent retry). The client should poll {@code reference}.
     */
    private Boolean existingOrder;

    /** Terms currently enabled for this client — helps the client recover from a 400. */
    private java.util.List<Integer> allowedTerms;

    public static BnplInitResponse success(String reference,
                                           String abbOrderId,
                                           String abbStatus,
                                           String message,
                                           Double amount,
                                           Integer term) {
        return success(reference, abbOrderId, abbStatus, message, amount, "AZN", term, false);
    }

    public static BnplInitResponse success(String reference,
                                           String abbOrderId,
                                           String abbStatus,
                                           String message,
                                           Double amount,
                                           String currency,
                                           Integer term,
                                           boolean existingOrder) {
        return BnplInitResponse.builder()
                .status("success")
                .reference(reference)
                .abbOrderId(abbOrderId)
                .abbStatus(abbStatus)
                .message(message)
                .amount(amount)
                .currency(currency != null ? currency : "AZN")
                .term(term)
                .retryable(false)
                .existingOrder(existingOrder)
                .build();
    }

    public static BnplInitResponse error(String errorCode, String errorMessage) {
        return error(errorCode, errorMessage, false, null);
    }

    public static BnplInitResponse error(String errorCode,
                                         String errorMessage,
                                         boolean retryable,
                                         java.util.List<Integer> allowedTerms) {
        return BnplInitResponse.builder()
                .status("error")
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .message(errorMessage)
                .retryable(retryable)
                .existingOrder(false)
                .allowedTerms(allowedTerms)
                .build();
    }
}
