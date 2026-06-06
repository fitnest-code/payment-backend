package az.fitnest.payment.dto.epoint;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record EpointInvoiceUpdateRequest(
    @Schema(hidden = true)
    @com.fasterxml.jackson.annotation.JsonProperty("public_key")
    String publicKey,
    Double sum,
    String display,
    @com.fasterxml.jackson.annotation.JsonProperty("save_as_template")
    Boolean saveAsTemplate,
    @com.fasterxml.jackson.annotation.JsonProperty("status_installment")
    Boolean statusInstallment,
    String name,
    String description,
    String phone,
    String email,
    String inn,
    @com.fasterxml.jackson.annotation.JsonProperty("contract_number")
    String contractNumber,
    @com.fasterxml.jackson.annotation.JsonProperty("merchant_order_id")
    String merchantOrderId,
    @com.fasterxml.jackson.annotation.JsonProperty("period_from")
    String periodFrom,
    @com.fasterxml.jackson.annotation.JsonProperty("period_to")
    String periodTo,
    @com.fasterxml.jackson.annotation.JsonProperty("invoice_images")
    List<String> invoiceImages,
    Long id
) {}
