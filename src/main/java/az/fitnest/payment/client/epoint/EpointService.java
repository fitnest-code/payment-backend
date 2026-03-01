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
        fillPublicKey(request);
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
        fillPublicKey(request);
        return httpClient.postSigned("/card-registration", request);
    }

    public EpointResponse executePay(EpointExecutePayRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/execute-pay", request);
    }

    public EpointResponse cardRegistrationWithPay(EpointPaymentRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/card-registration-with-pay", request);
    }

    public EpointResponse refundRequest(EpointExecutePayRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/refund-request", request);
    }

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
        fillPublicKey(request);
        return httpClient.postSigned("/split-request", request);
    }

    public EpointResponse createWidgetUrl(EpointPaymentRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/token/widget", request);
    }

    private void fillPublicKey(EpointRequestPayload request) {
        if (request.getPublicKey() == null) {
            request.setPublicKey(properties.getPublicKey());
        }
    }

    public EpointResponse splitExecutePay(EpointSplitExecutePayRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/split-execute-pay", request);
    }

    public EpointResponse splitCardRegistrationWithPay(EpointSplitPaymentRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/split-card-registration-with-pay", request);
    }

    public EpointResponse preAuthRequest(EpointPaymentRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/pre-auth-request", request);
    }

    public EpointResponse preAuthComplete(EpointPreAuthCompleteRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/pre-auth-complete", request);
    }

    public EpointResponse walletStatus() {
        EpointRequestPayload request = new EpointRequestPayload() {
        };
        fillPublicKey(request);
        return httpClient.postSigned("/wallet/status", request);
    }

    public EpointResponse walletPayment(EpointWalletPaymentRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/wallet/payment", request);
    }

    public EpointResponse createInvoice(EpointInvoiceCreateRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/invoices/create", request);
    }

    public EpointResponse updateInvoice(EpointInvoiceUpdateRequest request) {
        fillPublicKey(request);
        return httpClient.postSigned("/invoices/update", request);
    }

    public EpointResponse viewInvoice(Long id) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder().id(id).build();
        fillPublicKey(request);
        return httpClient.postSigned("/invoices/view", request);
    }

    public EpointResponse listInvoices(String type, String order) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder()
                .type(type)
                .order(order)
                .build();
        fillPublicKey(request);
        return httpClient.postSigned("/invoices/list", request);
    }

    public EpointResponse sendInvoiceSms(Long id, String phone) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder()
                .id(id)
                .phone(phone)
                .build();
        fillPublicKey(request);
        return httpClient.postSigned("/invoices/send-sms", request);
    }

    public EpointResponse sendInvoiceEmail(Long id, String email) {
        EpointInvoiceActionRequest request = EpointInvoiceActionRequest.builder()
                .id(id)
                .email(email)
                .build();
        fillPublicKey(request);
        return httpClient.postSigned("/invoices/send-email", request);
    }
}
