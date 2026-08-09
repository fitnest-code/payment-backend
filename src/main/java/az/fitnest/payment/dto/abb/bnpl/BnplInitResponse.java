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

    public static BnplInitResponse success(String reference,
                                           String abbOrderId,
                                           String abbStatus,
                                           String message,
                                           Double amount,
                                           Integer term) {
        return BnplInitResponse.builder()
                .status("success")
                .reference(reference)
                .abbOrderId(abbOrderId)
                .abbStatus(abbStatus)
                .message(message)
                .amount(amount)
                .currency("AZN")
                .term(term)
                .build();
    }

    public static BnplInitResponse error(String errorCode, String errorMessage) {
        return BnplInitResponse.builder()
                .status("error")
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .message(errorMessage)
                .build();
    }
}
