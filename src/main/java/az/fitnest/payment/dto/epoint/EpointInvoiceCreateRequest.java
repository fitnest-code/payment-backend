package az.fitnest.payment.dto.epoint;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

@Builder
public record EpointInvoiceCreateRequest(
    @Schema(hidden = true)
    String publicKey,
    Double sum,
    String display,
    Boolean saveAsTemplate,
    Boolean statusInstallment,
    String name,
    String description,
    String phone,
    String email,
    String inn,
    String contractNumber,
    String merchantOrderId,
    String periodFrom,
    String periodTo,
    List<String> invoiceImages
) {}
