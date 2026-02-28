package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EpointInvoiceCreateRequest extends EpointRequestPayload {
    private Double sum;
    private Integer display; // 1 or 0
    @JsonProperty("save_as_template")
    private Integer saveAsTemplate; // 1 or 0
    @JsonProperty("status_installment")
    private Integer statusInstallment; // 1 or 0
    private String name;
    private String description;
    private String phone;
    private String email;
    private String inn;
    @JsonProperty("contract_number")
    private String contractNumber;
    @JsonProperty("merchant_order_id")
    private String merchantOrderId;
    @JsonProperty("period_from")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate periodFrom;
    @JsonProperty("period_to")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate periodTo;
    @JsonProperty("invoice_images")
    private List<String> invoiceImages;
}
