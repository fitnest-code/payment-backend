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

import java.time.Instant;
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
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }

        Optional<Payment> existingPayment = paymentRepository.findByOrderId(request.orderId());
        if (existingPayment.isPresent()) {
            log.warn("Payment with orderId {} already exists", request.orderId());
            Payment payment = existingPayment.get();
            EpointResponse response = buildResponseFromPayment(payment);
            return idempotencyService.persistIdempotentResponse(idempotencyKey, response, payment);
        }

        EpointResponse response = epointService.createPayment(request);
        Payment payment = saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, payment);
    }

    @Transactional
    public EpointResponse cardRegistration(Long userId, EpointCardRegistrationRequest request) {
        String idempotencyKey = generateCardRegistrationKey(userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached card registration response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }

        EpointResponse response = epointService.cardRegistration(request);

        if ("success".equalsIgnoreCase(response.status()) && response.transaction() != null) {
            Payment tracking = new Payment();
            tracking.setProvider("EPOINT");
            tracking.setTransactionId(response.transaction());
            tracking.setStatus("PENDING_USER_ACTION");
            tracking.setUserId(userId);
            tracking.setDescription("card-registration");
            tracking.setRedirectUrl(response.redirectUrl());
            paymentRepository.save(tracking);
            log.info("Created card-registration tracking record. Transaction: {}, UserId: {}", response.transaction(), userId);
            return idempotencyService.persistIdempotentResponse(idempotencyKey, response, tracking);
        } else {
            return idempotencyService.persistIdempotentResponse(idempotencyKey, response, null);
        }
    }

    @Transactional
    public EpointResponse executePay(EpointExecutePayRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("execute-pay", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached execute-pay response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }

        Optional<Payment> existingPayment = paymentRepository.findByOrderId(request.orderId());
        if (existingPayment.isPresent()) {
            log.warn("Payment with orderId {} already exists", request.orderId());
            Payment payment = existingPayment.get();
            EpointResponse response = buildResponseFromPayment(payment);
            return idempotencyService.persistIdempotentResponse(idempotencyKey, response, payment);
        }

        EpointResponse response = epointService.executePay(request);
        Payment payment = saveDirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, null);
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, payment);
    }

    @Transactional
    public EpointResponse cardRegistrationWithPay(Long userId, EpointPaymentRequest request) {
        String idempotencyKey = generateIdempotencyKey("card-reg-pay", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached card-registration-with-pay response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }

        Optional<Payment> existingPayment = paymentRepository.findByOrderId(request.orderId());
        if (existingPayment.isPresent()) {
            log.warn("Payment with orderId {} already exists", request.orderId());
            Payment payment = existingPayment.get();
            EpointResponse response = buildResponseFromPayment(payment);
            return idempotencyService.persistIdempotentResponse(idempotencyKey, response, payment);
        }

        EpointResponse response = epointService.cardRegistrationWithPay(request);
        Payment payment = saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, payment);
    }

    @Transactional
    public EpointResponse refundRequest(EpointExecutePayRequest request) {
        EpointResponse response = epointService.refundRequest(request);
        if (response.transaction() != null) {
            paymentRepository.findByTransactionId(response.transaction()).ifPresent(payment -> {
                updatePaymentFromEpointResponse(payment, response);
                paymentRepository.save(payment);
            });
        }
        return response;
    }

    @Transactional
    public EpointResponse reverse(String transactionId, Double amount, String currency) {
        EpointResponse response = epointService.reverse(transactionId, amount, currency);
        paymentRepository.findByTransactionId(transactionId).ifPresent(payment -> {
            if ("success".equalsIgnoreCase(response.status())) {
                payment.setStatus("REVERSED");
            }
            payment.setMessage(response.message());
            paymentRepository.save(payment);
        });
        return response;
    }

    @Transactional
    public EpointResponse splitRequest(EpointSplitPaymentRequest request, Long userId) {
        EpointResponse response = epointService.splitRequest(request);
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        return response;
    }

    @Transactional
    public EpointResponse splitExecutePay(EpointSplitExecutePayRequest request, Long userId) {
        EpointResponse response = epointService.splitExecutePay(request);
        saveDirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, null);
        return response;
    }

    @Transactional
    public EpointResponse splitCardRegistrationWithPay(EpointSplitPaymentRequest request, Long userId) {
        EpointResponse response = epointService.splitCardRegistrationWithPay(request);
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        return response;
    }

    @Transactional
    public EpointResponse preAuthRequest(EpointPaymentRequest request, Long userId) {
        EpointResponse response = epointService.preAuthRequest(request);
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        return response;
    }

    @Transactional
    public EpointResponse preAuthComplete(EpointPreAuthCompleteRequest request) {
        EpointResponse response = epointService.preAuthComplete(request);
        if (request.transaction() != null) {
            paymentRepository.findByTransactionId(request.transaction()).ifPresent(payment -> {
                updatePaymentFromEpointResponse(payment, response);
                payment.setCallbackProcessed(true);
                paymentRepository.save(payment);
            });
        }
        return response;
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
        saveDirectPayment(response, request.orderId(), request.amount(), request.currency());
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
        if (base64Data == null || base64Data.isBlank()) {
            throw new IllegalArgumentException("Missing data parameter");
        }
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("Missing signature parameter");
        }

        if (!signer.verify(base64Data, signature, epointProperties.getPrivateKey())) {
            throw new SecurityException("Invalid callback signature");
        }

        EpointResponse callbackData = signer.decodeData(base64Data, EpointResponse.class);
        log.info("Processing Epoint callback for orderId: {}, transaction: {}", callbackData.orderId(), callbackData.transaction());

        try {
            Optional<Payment> optionalPayment = Optional.empty();
            if (callbackData.orderId() != null && !callbackData.orderId().isBlank()) {
                optionalPayment = paymentRepository.findByOrderId(callbackData.orderId());
            }
            if (optionalPayment.isEmpty() && callbackData.transaction() != null && !callbackData.transaction().isBlank()) {
                optionalPayment = paymentRepository.findByTransactionId(callbackData.transaction());
            }

            if (optionalPayment.isPresent()) {
                Payment payment = optionalPayment.get();

                if (Boolean.TRUE.equals(payment.getCallbackProcessed())) {
                    log.warn("Callback already processed for orderId: {}, transaction: {}. Skipping duplicate.",
                            callbackData.orderId(), callbackData.transaction());
                    return;
                }

                updatePaymentFromEpointResponse(payment, callbackData);
                payment.setCallbackProcessed(true);

                paymentRepository.save(payment);

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
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            log.warn("Concurrent callback detected for orderId: {}. Another thread already processed it. Ignoring.",
                    callbackData.orderId());
        } catch (jakarta.persistence.OptimisticLockException e) {
            log.warn("Concurrent callback detected for orderId: {} (JPA). Another thread already processed it. Ignoring.",
                    callbackData.orderId());
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

    @Transactional
    public EpointResponse getStatus(String transactionId) {
        EpointResponse response = epointService.getStatus(transactionId);

        paymentRepository.findByTransactionId(transactionId).ifPresent(payment -> {
            updatePaymentFromEpointResponse(payment, response);
            paymentRepository.save(payment);
        });

        return response;
    }

    private void updatePaymentFromEpointResponse(Payment payment, EpointResponse response) {
        payment.setStatus(response.status() != null ? response.status().toUpperCase() : payment.getStatus());
        payment.setTransactionId(response.transaction() != null ? response.transaction() : payment.getTransactionId());
        payment.setBankTransaction(response.bankTransaction());
        payment.setRrn(response.rrn());
        payment.setCardMask(response.cardMask());
        payment.setCardName(response.cardName());
        payment.setMessage(response.message());
        payment.setCode(response.code());
        payment.setBankResponse(response.bankResponse());
        payment.setOperationCode(response.operationCode());
    }

    private Payment saveRedirectPayment(EpointResponse response, String orderId, Double amount, String currency, Long userId, String description) {
        if ("success".equalsIgnoreCase(response.status())) {
            Payment payment = new Payment();
            payment.setProvider("EPOINT");
            payment.setOrderId(orderId);
            payment.setTransactionId(response.transaction());
            payment.setAmount(amount);
            payment.setCurrency(currency);
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

    private Payment saveRedirectPayment(EpointResponse response, String orderId, Double amount, String currency) {
        return saveRedirectPayment(response, orderId, amount, currency, null, null);
    }

    private Payment saveDirectPayment(EpointResponse response, String orderId, Double amount, String currency, Long userId, String description) {
        Payment payment = new Payment();
        payment.setProvider("EPOINT");
        payment.setOrderId(orderId);
        payment.setTransactionId(response.transaction());
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setStatus(response.status() != null ? response.status().toUpperCase() : "UNKNOWN");
        payment.setUserId(userId);
        payment.setDescription(description);
        payment.setCardMask(response.cardMask());
        payment.setCardName(response.cardName());
        payment.setRrn(response.rrn());
        payment.setBankTransaction(response.bankTransaction());
        payment.setBankResponse(response.bankResponse());
        payment.setOperationCode(response.operationCode());
        payment.setCode(response.code());
        payment.setMessage(response.message());
        payment.setCallbackProcessed(true);
        return paymentRepository.save(payment);
    }

    private Payment saveDirectPayment(EpointResponse response, String orderId, Double amount, String currency) {
        return saveDirectPayment(response, orderId, amount, currency, null, null);
    }

    private EpointResponse buildResponseFromPayment(Payment payment) {
        return EpointResponse.builder()
                .status(mapInternalStatusToExternalStatus(payment.getStatus()))
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

    private String mapInternalStatusToExternalStatus(String internalStatus) {
        if (internalStatus == null) {
            return null;
        }

        switch (internalStatus.toUpperCase()) {
            case "PENDING_USER_ACTION":
                return "success";

            case "SUCCESS":
                return "success";

            case "FAILED":
            case "ERROR":
            case "SERVER_ERROR":
                return internalStatus.toLowerCase();

            case "RETURNED":
            case "REFUNDED":
                return internalStatus.toLowerCase();

            default:
                return internalStatus.toLowerCase();
        }
    }

    private void upsertCardFromCallback(Long userId, EpointResponse callbackData) {
        if (callbackData.cardId() == null || callbackData.cardId().isBlank()) {
            return;
        }

        Optional<UserCard> existingCard = userCardRepository.findByUserIdAndCardId(userId, callbackData.cardId());

        if (existingCard.isPresent()) {
            UserCard card = existingCard.get();
            card.setCardMask(callbackData.cardMask());
            card.setCardName(callbackData.cardName());
            if (callbackData.cardMask() != null) {
                card.setBrand(CardBrandDetector.detectBrand(callbackData.cardMask()));
            }
            userCardRepository.save(card);
            log.info("Updated existing card {} for user {}", callbackData.cardId(), userId);
        } else {
            boolean isFirstCard = userCardRepository.findAllByUserId(userId).isEmpty();
            UserCard userCard = UserCard.builder()
                    .userId(userId)
                    .cardId(callbackData.cardId())
                    .cardMask(callbackData.cardMask())
                    .cardName(callbackData.cardName())
                    .brand(CardBrandDetector.detectBrand(callbackData.cardMask()))
                    .isDefault(isFirstCard)
                    .build();
            userCardRepository.save(userCard);
            log.info("Created new card {} for user {}", callbackData.cardId(), userId);
        }
    }

    private String generateIdempotencyKey(String operation, String orderId, Long userId) {
        return String.format("%s:%s:%s", operation, orderId, userId != null ? userId : "guest");
    }

    private String generateCardRegistrationKey(Long userId) {
        long timestampWindow = Instant.now().getEpochSecond() / 300;
        return String.format("card-registration:%s:%d",
            userId != null ? userId : "guest",
            timestampWindow);
    }
}
