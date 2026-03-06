package az.fitnest.payment.service;

import az.fitnest.payment.client.epoint.EpointService;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.client.epoint.EpointProperties;
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
    private final EpointProperties epointProperties;
    private final PaymentRepository paymentRepository;
    private final UserCardRepository userCardRepository;
    private final IdempotencyService idempotencyService;

    @Transactional
    public EpointResponse initiatePayment(EpointPaymentRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("payment", request.orderId(), userId);
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
    public EpointResponse cardRegistration(Long userId, EpointCardRegistrationRequest request) {
        // Card registration doesn't have orderId, so use userId + timestamp for idempotency
        String idempotencyKey = generateCardRegistrationKey(userId);
        // Check for cached response
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached card registration response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }

        EpointResponse response = epointService.cardRegistration(request);
        // Do NOT save card here - wait for callback success
        // Only store the response for idempotency, not the card
        idempotencyService.storeResponse(idempotencyKey, response, null);
        return response;
    }

    @Transactional
    public EpointResponse executePay(EpointExecutePayRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("execute-pay", request.orderId(), userId);
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
    public EpointResponse cardRegistrationWithPay(Long userId, EpointPaymentRequest request) {
        String idempotencyKey = generateIdempotencyKey("card-reg-pay", request.orderId(), userId);
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
        // Do NOT save card here - wait for callback success to activate the card
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
    public void processCallback(String base64Data, String signature) {
        // Validate parameters
        if (base64Data == null || base64Data.isBlank()) {
            throw new IllegalArgumentException("Missing data parameter");
        }
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("Missing signature parameter");
        }

        // Verify signature (CRITICAL FOR SECURITY)
        if (!signer.verify(base64Data, signature, epointProperties.getPrivateKey())) {
            throw new SecurityException("Invalid callback signature");
        }

        EpointResponse callbackData = signer.decodeData(base64Data, EpointResponse.class);
        log.info("Processing Epoint callback for orderId: {}, transaction: {}", callbackData.orderId(), callbackData.transaction());

        Optional<Payment> optionalPayment = paymentRepository.findByOrderId(callbackData.orderId());

        if (optionalPayment.isPresent()) {
            Payment payment = optionalPayment.get();

            // Check if callback was already processed (idempotency)
            if (Boolean.TRUE.equals(payment.getCallbackProcessed())) {
                log.warn("Callback already processed for orderId: {}, transaction: {}. Skipping duplicate.",
                        callbackData.orderId(), callbackData.transaction());
                return;
            }

            updatePaymentFromEpointResponse(payment, callbackData);
            payment.setCallbackProcessed(true);
            paymentRepository.save(payment);

            // CRITICAL: Attach card to user on successful callback
            // This is the authoritative point where card is confirmed valid
            if ("success".equalsIgnoreCase(callbackData.status()) && callbackData.cardId() != null) {
                Long userId = payment.getUserId();
                if (userId != null) {
                    upsertCardFromCallback(userId, callbackData);
                    log.info("Card attached to user {} from callback. Card ID: {}", userId, callbackData.cardId());
                } else {
                    log.warn("Cannot attach card: payment has no userId. OrderId: {}", callbackData.orderId());
                }
            }

            log.info("Payment updated from callback. OrderId: {}, Status: {}", callbackData.orderId(), callbackData.status());
        } else {
            log.warn("No payment found for orderId: {} in callback. Creating new record.", callbackData.orderId());
            // Create a new payment record if not initiated from our backend
            Payment payment = new Payment();
            payment.setProvider("EPOINT");
            payment.setOrderId(callbackData.orderId());
            payment.setTransactionId(callbackData.transaction());
            payment.setAmount(callbackData.amount());
            payment.setCurrency("AZN");
            updatePaymentFromEpointResponse(payment, callbackData);
            payment.setCallbackProcessed(true);
            paymentRepository.save(payment);
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
            // Status is PENDING_USER_ACTION because initial response means request accepted/redirect created,
            // not final payment success. Final result comes via callback.
            payment.setStatus("PENDING_USER_ACTION");
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

    /**
     * Upsert (insert or update) a card from callback data.
     * This is the authoritative point where a card is confirmed valid by Epoint.
     *
     * - If card_id doesn't exist: create new UserCard
     * - If card_id exists: update card details (mask, name) in case they changed
     * - Set isDefault=true if this is user's first card
     */
    private void upsertCardFromCallback(Long userId, EpointResponse callbackData) {
        if (callbackData.cardId() == null || callbackData.cardId().isBlank()) {
            return;
        }

        Optional<UserCard> existingCard = userCardRepository.findByCardId(callbackData.cardId());

        if (existingCard.isPresent()) {
            // Update existing card with latest data from callback
            UserCard card = existingCard.get();
            card.setCardMask(callbackData.cardMask());
            card.setCardName(callbackData.cardName());
            if (callbackData.cardMask() != null) {
                card.setBrand(CardBrandDetector.detectBrand(callbackData.cardMask()));
            }
            userCardRepository.save(card);
            log.info("Updated existing card {} for user {}", callbackData.cardId(), userId);
        } else {
            // Create new card
            boolean isFirstCard = userCardRepository.findAllByUserId(userId).isEmpty();
            UserCard userCard = UserCard.builder()
                    .userId(userId)
                    .cardId(callbackData.cardId())
                    .cardMask(callbackData.cardMask())
                    .cardName(callbackData.cardName())
                    .brand(CardBrandDetector.detectBrand(callbackData.cardMask()))
                    .isDefault(isFirstCard) // First card is default
                    .build();
            userCardRepository.save(userCard);
            log.info("Created new card {} for user {}", callbackData.cardId(), userId);
        }
    }

    /**
     * Generate idempotency key based on operation type, orderId, and userId.
     * Ensures the same request will always generate the same key.
     * Format: {operation}:{orderId}:{userId}
     */
    private String generateIdempotencyKey(String operation, String orderId, Long userId) {
        return String.format("%s:%s:%s", operation, orderId, userId != null ? userId : "guest");
    }

    /**
     * Generate idempotency key for card registration (no orderId).
     * Since card registration doesn't have orderId, use userId and operation type.
     * Format: card-registration:{userId}
     */
    private String generateCardRegistrationKey(Long userId) {
        return String.format("card-registration:%s", userId != null ? userId : "guest");
    }
}
