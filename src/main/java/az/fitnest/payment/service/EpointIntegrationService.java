package az.fitnest.payment.service;

import az.fitnest.payment.client.epoint.EpointService;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.dto.epoint.*;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EpointIntegrationService {

    private final EpointService epointService;
    private final EpointSigner signer;
    private final PaymentRepository paymentRepository;

    @Transactional
    public EpointResponse initiatePayment(EpointPaymentRequest request) {
        EpointResponse response = epointService.createPayment(request);
        savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency());
        return response;
    }

    @Transactional
    public EpointResponse cardRegistration(EpointPaymentRequest request) {
        return epointService.cardRegistration(request);
    }

    @Transactional
    public EpointResponse executePay(EpointExecutePayRequest request) {
        EpointResponse response = epointService.executePay(request);
        savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency());
        return response;
    }

    @Transactional
    public EpointResponse cardRegistrationWithPay(EpointPaymentRequest request) {
        EpointResponse response = epointService.cardRegistrationWithPay(request);
        savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency());
        return response;
    }

    @Transactional
    public EpointResponse refundRequest(EpointExecutePayRequest request) {
        return epointService.refundRequest(request);
    }

    @Transactional
    public EpointResponse reverse(String transactionId, Double amount, String currency) {
        return epointService.reverse(transactionId, amount, currency);
    }

    @Transactional
    public EpointResponse splitRequest(EpointSplitPaymentRequest request) {
        EpointResponse response = epointService.splitRequest(request);
        savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency());
        return response;
    }

    @Transactional
    public EpointResponse splitExecutePay(EpointSplitExecutePayRequest request) {
        EpointResponse response = epointService.splitExecutePay(request);
        savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency());
        return response;
    }

    @Transactional
    public EpointResponse splitCardRegistrationWithPay(EpointSplitPaymentRequest request) {
        EpointResponse response = epointService.splitCardRegistrationWithPay(request);
        savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency());
        return response;
    }

    @Transactional
    public EpointResponse preAuthRequest(EpointPaymentRequest request) {
        EpointResponse response = epointService.preAuthRequest(request);
        savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency());
        return response;
    }

    @Transactional
    public EpointResponse preAuthComplete(EpointPreAuthCompleteRequest request) {
        return epointService.preAuthComplete(request);
    }

    @Transactional
    public EpointResponse createWidgetUrl(EpointPaymentRequest request) {
        return epointService.createWidgetUrl(request);
    }

    public EpointResponse walletStatus() {
        return epointService.walletStatus();
    }

    @Transactional
    public EpointResponse walletPayment(EpointWalletPaymentRequest request) {
        EpointResponse response = epointService.walletPayment(request);
        savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency());
        return response;
    }

    @Transactional
    public EpointResponse createInvoice(EpointInvoiceCreateRequest request) {
        return epointService.createInvoice(request);
    }

    @Transactional
    public EpointResponse updateInvoice(EpointInvoiceUpdateRequest request) {
        return epointService.updateInvoice(request);
    }

    public EpointResponse viewInvoice(Long id) {
        return epointService.viewInvoice(id);
    }

    public EpointResponse listInvoices(String type, String order) {
        return epointService.listInvoices(type, order);
    }

    public EpointResponse sendInvoiceSms(Long id, String phone) {
        return epointService.sendInvoiceSms(id, phone);
    }

    public EpointResponse sendInvoiceEmail(Long id, String email) {
        return epointService.sendInvoiceEmail(id, email);
    }

    @Transactional
    public void processCallback(String base64Data) {
        EpointResponse callbackData = signer.decodeData(base64Data, EpointResponse.class);

        Optional<Payment> optionalPayment = paymentRepository.findByOrderId(callbackData.orderId());

        if (optionalPayment.isPresent()) {
            Payment payment = optionalPayment.get();
            updatePaymentFromEpointResponse(payment, callbackData);
            paymentRepository.save(payment);
        } else {
            // Optionally create a new record if your logic allows
        }
    }

    @Transactional
    public void syncStatus(String transactionId) {
        EpointResponse response = epointService.getStatus(transactionId);

        paymentRepository.findByTransactionId(transactionId).ifPresent(payment -> {
            updatePaymentFromEpointResponse(payment, response);
            paymentRepository.save(payment);
        });
    }

    private void updatePaymentFromEpointResponse(Payment payment, EpointResponse response) {
        payment.setStatus(response.status() != null ? response.status().toUpperCase() : payment.getStatus());
        payment.setTransactionId(response.transaction() != null ? response.transaction() : payment.getTransactionId());
        payment.setBankTransaction(response.bankTransaction());
        payment.setRrn(response.rrn());
        payment.setCardMask(response.cardMask());
        payment.setCardName(response.cardName());
        payment.setMessage(response.message());
        // Map Epoint status success to our internal code if needed
    }

    private void savePaymentIfSuccess(EpointResponse response, String orderId, Double amount, String currency) {
        if ("success".equalsIgnoreCase(response.status())) {
            Payment payment = new Payment();
            payment.setProvider("EPOINT");
            payment.setOrderId(orderId);
            payment.setTransactionId(response.transaction());
            payment.setAmount(amount);
            payment.setCurrency(currency);
            payment.setStatus("NEW");
            paymentRepository.save(payment);
        }
    }
}
