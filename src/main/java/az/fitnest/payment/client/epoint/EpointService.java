package az.fitnest.payment.client.epoint;

import az.fitnest.payment.client.epoint.dto.*;
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
        EpointResponse requestBody = new EpointResponse(); // Using EpointResponse as a generic map holder or creating a dedicated one
        // For simplicity, let's use a dedicated DTO if reverse has specific fields
        return httpClient.postSigned("/reverse", EpointStatusRequest.builder()
                .publicKey(properties.getPublicKey())
                .transaction(transactionId)
                .build());
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
}
