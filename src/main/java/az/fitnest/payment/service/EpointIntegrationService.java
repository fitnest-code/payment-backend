package az.fitnest.payment.service;

import az.fitnest.payment.client.epoint.EpointService;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.dto.epoint.*;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import az.fitnest.payment.util.CardBrandDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EpointIntegrationService {

    private final EpointService epointService;
    private final EpointSigner signer;
    private final PaymentRepository paymentRepository;
    private final UserCardRepository userCardRepository;
    private final IdempotencyService idempotencyService;

    @Transactional
    public EpointResponse initiatePayment(String idempotencyKey, EpointPaymentRequest request, Long userId) {
        // Check for cached response
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }

        // Check if payment with this orderId already exists
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(request.orderId());
        if (existingPayment.isPresent()) {
            log.warn("Payment with orderId {} already exists", request.orderId());
            Payment payment = existingPayment.get();
            EpointResponse response = buildResponseFromPayment(payment);
            idempotencyService.storeResponse(idempotencyKey, response, payment);
            return response;
        }

        // Proceed with new payment
        EpointResponse response = epointService.createPayment(request);
        Payment payment = savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        idempotencyService.storeResponse(idempotencyKey, response, payment);
        return response;
    }

    @Transactional
    public EpointResponse cardRegistration(String idempotencyKey, Long userId, EpointPaymentRequest request) {
        // Check for cached response
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached card registration response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }

        EpointResponse response = epointService.cardRegistration(request);
        saveCardIfProvided(userId, response);
        idempotencyService.storeResponse(idempotencyKey, response, null);
        return response;
    }

    @Transactional
    public EpointResponse executePay(String idempotencyKey, EpointExecutePayRequest request, Long userId) {
        // Check for cached response
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached execute-pay response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }

        // Check if payment with this orderId already exists
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(request.orderId());
        if (existingPayment.isPresent()) {
            log.warn("Payment with orderId {} already exists", request.orderId());
            Payment payment = existingPayment.get();
            EpointResponse response = buildResponseFromPayment(payment);
            idempotencyService.storeResponse(idempotencyKey, response, payment);
            return response;
        }

        EpointResponse response = epointService.executePay(request);
        Payment payment = savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency(), userId, null);
        idempotencyService.storeResponse(idempotencyKey, response, payment);
        return response;
    }

    @Transactional
    public EpointResponse cardRegistrationWithPay(String idempotencyKey, Long userId, EpointPaymentRequest request) {
        // Check for cached response
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached card-registration-with-pay response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }

        // Check if payment with this orderId already exists
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(request.orderId());
        if (existingPayment.isPresent()) {
            log.warn("Payment with orderId {} already exists", request.orderId());
            Payment payment = existingPayment.get();
            EpointResponse response = buildResponseFromPayment(payment);
            idempotencyService.storeResponse(idempotencyKey, response, payment);
            return response;
        }

        EpointResponse response = epointService.cardRegistrationWithPay(request);
        Payment payment = savePaymentIfSuccess(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        saveCardIfProvided(userId, response);
        idempotencyService.storeResponse(idempotencyKey, response, payment);
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

    public EpointResponse getStatus(String transactionId) {
        return epointService.getStatus(transactionId);
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

    private Payment savePaymentIfSuccess(EpointResponse response, String orderId, Double amount, String currency, Long userId, String description) {
        if ("success".equalsIgnoreCase(response.status())) {
            Payment payment = new Payment();
            payment.setProvider("EPOINT");
            payment.setOrderId(orderId);
            payment.setTransactionId(response.transaction());
            payment.setAmount(amount);
            payment.setCurrency(currency);
            payment.setStatus("NEW");
            payment.setUserId(userId);
            payment.setDescription(description);
            payment.setRedirectUrl(response.redirectUrl());
            payment.setCardMask(response.cardMask());
            payment.setCardName(response.cardName());
            payment.setRrn(response.rrn());
            payment.setBankTransaction(response.bankTransaction());
            payment.setMessage(response.message());
            return paymentRepository.save(payment);
        }
        return null;
    }

    private void savePaymentIfSuccess(EpointResponse response, String orderId, Double amount, String currency) {
        savePaymentIfSuccess(response, orderId, amount, currency, null, null);
    }

    private EpointResponse buildResponseFromPayment(Payment payment) {
        return EpointResponse.builder()
                .status(payment.getStatus())
                .transaction(payment.getTransactionId())
                .orderId(payment.getOrderId())
                .redirectUrl(payment.getRedirectUrl())
                .bankTransaction(payment.getBankTransaction())
                .rrn(payment.getRrn())
                .cardName(payment.getCardName())
                .cardMask(payment.getCardMask())
                .amount(payment.getAmount())
                .message(payment.getMessage())
                .build();
    }

    private void saveCardIfProvided(Long userId, EpointResponse response) {
        if (response.cardId() != null && !response.cardId().isBlank()) {
            // Check if card already exists to avoid duplicates
            if (userCardRepository.findByCardId(response.cardId()).isEmpty()) {
                UserCard userCard = UserCard.builder()
                        .userId(userId)
                        .cardId(response.cardId())
                        .cardMask(response.cardMask())
                        .cardName(response.cardName())
                        .brand(CardBrandDetector.detectBrand(response.cardMask()))
                        .isDefault(userCardRepository.findAllByUserId(userId).isEmpty()) // First card is default
                        .build();
                userCardRepository.save(userCard);
            }
        }
    }
}
