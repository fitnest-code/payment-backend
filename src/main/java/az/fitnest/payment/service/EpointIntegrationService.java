package az.fitnest.payment.service;

import az.fitnest.payment.client.epoint.EpointService;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.client.epoint.dto.EpointPaymentRequest;
import az.fitnest.payment.client.epoint.dto.EpointResponse;
import az.fitnest.payment.entity.Payment;
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
        
        if ("success".equalsIgnoreCase(response.getStatus())) {
            Payment payment = new Payment();
            payment.setProvider("EPOINT");
            payment.setOrderId(request.getOrderId());
            payment.setTransactionId(response.getTransaction());
            payment.setAmount(request.getAmount());
            payment.setCurrency(request.getCurrency());
            payment.setStatus("NEW");
            paymentRepository.save(payment);
        }
        
        return response;
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
}
