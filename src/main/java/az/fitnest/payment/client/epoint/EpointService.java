package az.fitnest.payment.client.epoint;

import az.fitnest.payment.dto.epoint.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EpointService {

    private final EpointHttpClient httpClient;
    private final EpointProperties properties;

    public EpointResponse createPayment(EpointPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/request", request);
    }

    public EpointResponse getStatus(String transactionId) {
        EpointStatusRequest request = EpointStatusRequest.builder()
                .publicKey(properties.getPublicKey())
                .transaction(transactionId)
                .build();
        return httpClient.postSigned("/get-status", request);
    }

    public EpointResponse cardRegistration(EpointPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/card-registration", request);
    }

    public EpointResponse cardRegistration(EpointCardRegistrationRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/card-registration", request);
    }

    public EpointResponse executePay(EpointExecutePayRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/execute-pay", request);
    }

    public EpointResponse cardRegistrationWithPay(EpointPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/card-registration-with-pay", request);
    }

    public EpointResponse refundRequest(EpointExecutePayRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/refund-request", request);
    }

    /**
     * Reverse a transaction (full or partial).
     * When amount is less than the original transaction amount, a partial reversal is performed.
     */
    public EpointResponse reverse(String transactionId, Double amount, String currency) {
        EpointReverseRequest request = EpointReverseRequest.builder()
                .publicKey(properties.getPublicKey())
                .language("en") // Defaulting to english, could be parameterized
                .transaction(transactionId)
                .amount(amount)
                .currency(currency)
                .build();
        return httpClient.postSigned("/reverse", request);
    }

    public EpointResponse splitRequest(EpointSplitPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/split-request", request);
    }

    public EpointResponse createWidgetUrl(EpointPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/token/widget", request);
    }

    private EpointPaymentRequest fillPublicKey(EpointPaymentRequest request) {
        if (request.publicKey() == null) {
            return EpointPaymentRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .language(request.language())
                    .orderId(request.orderId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .description(request.description())
                    .successRedirectUrl(request.successRedirectUrl())
                    .errorRedirectUrl(request.errorRedirectUrl())
                    .isInstallment(request.isInstallment())
                    .refund(request.refund())
                    .otherAttr(request.otherAttr())
                    .build();
        }
        return request;
    }

    private EpointCardRegistrationRequest fillPublicKey(EpointCardRegistrationRequest request) {
        if (request.publicKey() == null) {
            return EpointCardRegistrationRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .language(request.language())
                    .refund(request.refund())
                    .description(request.description())
                    .successRedirectUrl(request.successRedirectUrl())
                    .errorRedirectUrl(request.errorRedirectUrl())
                    .build();
        }
        return request;
    }

    private EpointExecutePayRequest fillPublicKey(EpointExecutePayRequest request) {
        if (request.publicKey() == null) {
            return EpointExecutePayRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .language(request.language())
                    .orderId(request.orderId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .description(request.description())
                    .successRedirectUrl(request.successRedirectUrl())
                    .errorRedirectUrl(request.errorRedirectUrl())
                    .cardId(request.cardId())
                    .isInstallment(request.isInstallment())
                    .build();
        }
        return request;
    }

    private EpointSplitPaymentRequest fillPublicKey(EpointSplitPaymentRequest request) {
        if (request.publicKey() == null) {
            return EpointSplitPaymentRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .language(request.language())
                    .orderId(request.orderId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .description(request.description())
                    .successRedirectUrl(request.successRedirectUrl())
                    .errorRedirectUrl(request.errorRedirectUrl())
                    .splitUser(request.splitUser())
                    .splitAmount(request.splitAmount())
                    .build();
        }
        return request;
    }

    private EpointSplitExecutePayRequest fillPublicKey(EpointSplitExecutePayRequest request) {
        if (request.publicKey() == null) {
            return EpointSplitExecutePayRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .language(request.language())
                    .orderId(request.orderId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .description(request.description())
                    .successRedirectUrl(request.successRedirectUrl())
                    .errorRedirectUrl(request.errorRedirectUrl())
                    .splitUser(request.splitUser())
                    .splitAmount(request.splitAmount())
                    .cardId(request.cardId())
                    .build();
        }
        return request;
    }

    private EpointPreAuthCompleteRequest fillPublicKey(EpointPreAuthCompleteRequest request) {
        if (request.publicKey() == null) {
            return EpointPreAuthCompleteRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .amount(request.amount())
                    .transaction(request.transaction())
                    .build();
        }
        return request;
    }

    private EpointWalletPaymentRequest fillPublicKey(EpointWalletPaymentRequest request) {
        if (request.publicKey() == null) {
            return EpointWalletPaymentRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .walletId(request.walletId())
                    .amount(request.amount())
                    .currency(request.currency())
                    .orderId(request.orderId())
                    .description(request.description())
                    .language(request.language())
                    .build();
        }
        return request;
    }

    private EpointInvoiceCreateRequest fillPublicKey(EpointInvoiceCreateRequest request) {
        if (request.publicKey() == null) {
            return EpointInvoiceCreateRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .sum(request.sum())
                    .display(request.display())
                    .saveAsTemplate(request.saveAsTemplate())
                    .statusInstallment(request.statusInstallment())
                    .name(request.name())
                    .description(request.description())
                    .phone(request.phone())
                    .email(request.email())
                    .inn(request.inn())
                    .contractNumber(request.contractNumber())
                    .merchantOrderId(request.merchantOrderId())
                    .periodFrom(request.periodFrom())
                    .periodTo(request.periodTo())
                    .invoiceImages(request.invoiceImages())
                    .build();
        }
        return request;
    }

    private EpointInvoiceUpdateRequest fillPublicKey(EpointInvoiceUpdateRequest request) {
        if (request.publicKey() == null) {
            return EpointInvoiceUpdateRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .sum(request.sum())
                    .display(request.display())
                    .saveAsTemplate(request.saveAsTemplate())
                    .statusInstallment(request.statusInstallment())
                    .name(request.name())
                    .description(request.description())
                    .phone(request.phone())
                    .email(request.email())
                    .inn(request.inn())
                    .contractNumber(request.contractNumber())
                    .merchantOrderId(request.merchantOrderId())
                    .periodFrom(request.periodFrom())
                    .periodTo(request.periodTo())
                    .invoiceImages(request.invoiceImages())
                    .id(request.id())
                    .build();
        }
        return request;
    }

    private EpointInvoiceActionRequest fillPublicKey(EpointInvoiceActionRequest request) {
        if (request.publicKey() == null) {
            return EpointInvoiceActionRequest.builder()
                    .publicKey(properties.getPublicKey())
                    .id(request.id())
                    .phone(request.phone())
                    .email(request.email())
                    .type(request.type())
                    .order(request.order())
                    .build();
        }
        return request;
    }

    public EpointResponse splitExecutePay(EpointSplitExecutePayRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/split-execute-pay", request);
    }

    public EpointResponse splitCardRegistrationWithPay(EpointSplitPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/split-card-registration-with-pay", request);
    }

    public EpointResponse preAuthRequest(EpointPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/pre-auth-request", request);
    }

    public EpointResponse preAuthComplete(EpointPreAuthCompleteRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/pre-auth-complete", request);
    }

    /**
     * Get wallet status from Epoint.
     *
     * IMPORTANT: This endpoint currently uses the standard data+signature envelope format.
     * According to the PDF documentation, this endpoint may require a different format
     * (direct public_key parameter without envelope). If you encounter format errors,
     * try using httpClient.postDirect() instead of httpClient.postSigned().
     *
     * See: WALLET_STATUS_ENDPOINT_ANALYSIS.md for details.
     *
     * @return EpointResponse containing wallet status information
     */
    public EpointResponse walletStatus() {
        EpointRequestPayload request = EpointRequestPayload.builder()
                .publicKey(properties.getPublicKey())
                .build();
        // TODO: Verify with Epoint PDF documentation if this should use postDirect() instead
        return httpClient.postSigned("/wallet/status", request);
    }

    public EpointResponse walletPayment(EpointWalletPaymentRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/wallet/payment", request);
    }

    public EpointResponse createInvoice(EpointInvoiceCreateRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/create", request);
    }

    public EpointResponse updateInvoice(EpointInvoiceUpdateRequest request) {
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/update", request);
    }

    public EpointResponse viewInvoice(Long id) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder().id(id).build();
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/view", request);
    }

    public EpointResponse listInvoices(String type, String order) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder()
                .type(type)
                .order(order)
                .build();
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/list", request);
    }

    public EpointResponse sendInvoiceSms(Long id, String phone) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder()
                .id(id)
                .phone(phone)
                .build();
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/send-sms", request);
    }

    public EpointResponse sendInvoiceEmail(Long id, String email) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder()
                .id(id)
                .email(email)
                .build();
        request = fillPublicKey(request);
        return httpClient.postSigned("/invoices/send-email", request);
    }
}
