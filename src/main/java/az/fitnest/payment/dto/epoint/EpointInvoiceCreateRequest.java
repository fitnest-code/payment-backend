package az.fitnest.payment.dto.epoint;

import java.util.List;

public record EpointInvoiceCreateRequest(
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
