package az.fitnest.payment.dto.epoint;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

@Builder
public record EpointInvoiceCreateRequest(
    @JsonProperty("public_key")
    String publicKey,
    Double sum,
    Integer display,
    @JsonProperty("save_as_template")
    Integer saveAsTemplate,
    @JsonProperty("status_installment")
    Integer statusInstallment,
    String name,
    String description,
    String phone,
    String email,
    String inn,
    @JsonProperty("contract_number")
    String contractNumber,
    @JsonProperty("merchant_order_id")
    String merchantOrderId,
    @JsonProperty("period_from")
    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate periodFrom,
    @JsonProperty("period_to")
    @JsonFormat(pattern = "yyyy-MM-dd")
    LocalDate periodTo,
    @JsonProperty("invoice_images")
    List<String> invoiceImages
) {}
