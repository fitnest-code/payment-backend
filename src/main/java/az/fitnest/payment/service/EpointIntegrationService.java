package az.fitnest.payment.service;

import az.fitnest.payment.client.epoint.EpointService;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.dto.epoint.*;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EpointIntegrationService {

    private final EpointService epointService;
    private final EpointSigner signer;
    private final PaymentRepository paymentRepository;

    @Transactional
    public EpointResponse initiatePayment(EpointPaymentRequest request) {
        log.info("Initiating Epoint payment for order: {}", request.getOrderId());
        EpointResponse response = epointService.createPayment(request);
        savePaymentIfSuccess(response, request.getOrderId(), request.getAmount(), request.getCurrency());
        return response;
    }

    @Transactional
    public EpointResponse cardRegistration(EpointPaymentRequest request) {
        log.info("Initiating Epoint card registration for order: {}", request.getOrderId());
        return epointService.cardRegistration(request);
    }

    @Transactional
    public EpointResponse executePay(EpointExecutePayRequest request) {
        log.info("Executing Epoint payment for order: {}, cardId: {}", request.getOrderId(), request.getCardId());
        EpointResponse response = epointService.executePay(request);
        savePaymentIfSuccess(response, request.getOrderId(), request.getAmount(), request.getCurrency());
        return response;
    }

    @Transactional
    public EpointResponse cardRegistrationWithPay(EpointPaymentRequest request) {
        log.info("Initiating Epoint card registration with pay for order: {}", request.getOrderId());
        EpointResponse response = epointService.cardRegistrationWithPay(request);
        savePaymentIfSuccess(response, request.getOrderId(), request.getAmount(), request.getCurrency());
        return response;
    }

    @Transactional
    public EpointResponse refundRequest(EpointExecutePayRequest request) {
        log.info("Initiating Epoint refund request for order: {}", request.getOrderId());
        return epointService.refundRequest(request);
    }

    @Transactional
    public EpointResponse reverse(String transactionId, Double amount, String currency) {
        log.info("Initiating Epoint reverse for transaction: {}", transactionId);
        return epointService.reverse(transactionId, amount, currency);
    }

    @Transactional
    public EpointResponse splitRequest(EpointSplitPaymentRequest request) {
        log.info("Initiating Epoint split request for order: {}", request.getOrderId());
        EpointResponse response = epointService.splitRequest(request);
        savePaymentIfSuccess(response, request.getOrderId(), request.getAmount(), request.getCurrency());
        return response;
    }

    @Transactional
    public EpointResponse splitExecutePay(EpointSplitExecutePayRequest request) {
        log.info("Executing Epoint split payment for order: {}, cardId: {}", request.getOrderId(), request.getCardId());
        EpointResponse response = epointService.splitExecutePay(request);
        savePaymentIfSuccess(response, request.getOrderId(), request.getAmount(), request.getCurrency());
        return response;
    }

    @Transactional
    public EpointResponse splitCardRegistrationWithPay(EpointSplitPaymentRequest request) {
        log.info("Initiating Epoint split card registration with pay for order: {}", request.getOrderId());
        EpointResponse response = epointService.splitCardRegistrationWithPay(request);
        savePaymentIfSuccess(response, request.getOrderId(), request.getAmount(), request.getCurrency());
        return response;
    }

    @Transactional
    public EpointResponse preAuthRequest(EpointPaymentRequest request) {
        log.info("Initiating Epoint pre auth request for order: {}", request.getOrderId());
        EpointResponse response = epointService.preAuthRequest(request);
        savePaymentIfSuccess(response, request.getOrderId(), request.getAmount(), request.getCurrency());
        return response;
    }

    @Transactional
    public EpointResponse preAuthComplete(EpointPreAuthCompleteRequest request) {
        log.info("Completing Epoint pre auth for transaction: {}", request.getTransaction());
        return epointService.preAuthComplete(request);
    }

    @Transactional
    public EpointResponse createWidgetUrl(EpointPaymentRequest request) {
        log.info("Creating Epoint widget URL for order: {}", request.getOrderId());
        return epointService.createWidgetUrl(request);
    }

    public EpointResponse walletStatus() {
        log.info("Fetching Epoint wallet status");
        return epointService.walletStatus();
    }

    @Transactional
    public EpointResponse walletPayment(EpointWalletPaymentRequest request) {
        log.info("Initiating Epoint wallet payment for order: {}", request.getOrderId());
        EpointResponse response = epointService.walletPayment(request);
        savePaymentIfSuccess(response, request.getOrderId(), request.getAmount(), request.getCurrency());
        return response;
    }

    @Transactional
    public EpointResponse createInvoice(EpointInvoiceCreateRequest request) {
        log.info("Creating Epoint invoice for sum: {}", request.getSum());
        return epointService.createInvoice(request);
    }

    @Transactional
    public EpointResponse updateInvoice(EpointInvoiceUpdateRequest request) {
        log.info("Updating Epoint invoice id: {}", request.getId());
        return epointService.updateInvoice(request);
    }

    public EpointResponse viewInvoice(Long id) {
        log.info("Viewing Epoint invoice id: {}", id);
        return epointService.viewInvoice(id);
    }

    public EpointResponse listInvoices(String type, String order) {
        log.info("Listing Epoint invoices type: {}, order: {}", type, order);
        return epointService.listInvoices(type, order);
    }

    public EpointResponse sendInvoiceSms(Long id, String phone) {
        log.info("Sending Epoint invoice SMS id: {}", id);
        return epointService.sendInvoiceSms(id, phone);
    }

    public EpointResponse sendInvoiceEmail(Long id, String email) {
        log.info("Sending Epoint invoice email id: {}", id);
        return epointService.sendInvoiceEmail(id, email);
    }

    @Transactional
    public void processCallback(String base64Data) {
        EpointResponse callbackData = signer.decodeData(base64Data, EpointResponse.class);
        log.info("Processing Epoint callback for order: {}, status: {}",
                callbackData.getOrderId(), callbackData.getStatus());

        Optional<Payment> optionalPayment = paymentRepository.findByOrderId(callbackData.getOrderId());

        if (optionalPayment.isPresent()) {
            Payment payment = optionalPayment.get();
            updatePaymentFromEpointResponse(payment, callbackData);
            paymentRepository.save(payment);
            log.info("Payment updated for order: {}", callbackData.getOrderId());
        } else {
            log.warn("Payment not found for orderId: {}", callbackData.getOrderId());
            // Optionally create a new record if your logic allows
        }
    }

    @Transactional
    public void syncStatus(String transactionId) {
        log.info("Syncing status for transaction: {}", transactionId);
        EpointResponse response = epointService.getStatus(transactionId);

        paymentRepository.findByTransactionId(transactionId).ifPresent(payment -> {
            updatePaymentFromEpointResponse(payment, response);
            paymentRepository.save(payment);
        });
    }

    private void updatePaymentFromEpointResponse(Payment payment, EpointResponse response) {
        payment.setStatus(response.getStatus() != null ? response.getStatus().toUpperCase() : payment.getStatus());
        payment.setTransactionId(response.getTransaction() != null ? response.getTransaction() : payment.getTransactionId());
        payment.setBankTransaction(response.getBankTransaction());
        payment.setRrn(response.getRrn());
        payment.setCardMask(response.getCardMask());
        payment.setCardName(response.getCardName());
        payment.setMessage(response.getMessage());
        // Map Epoint status success to our internal code if needed
    }

    private void savePaymentIfSuccess(EpointResponse response, String orderId, Double amount, String currency) {
        if ("success".equalsIgnoreCase(response.getStatus())) {
            Payment payment = new Payment();
            payment.setProvider("EPOINT");
            payment.setOrderId(orderId);
            payment.setTransactionId(response.getTransaction());
            payment.setAmount(amount);
            payment.setCurrency(currency);
            payment.setStatus("NEW");
            paymentRepository.save(payment);
        }
    }
}
