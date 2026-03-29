package az.fitnest.payment.service;

import az.fitnest.payment.client.epoint.EpointService;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.client.epoint.EpointProperties;
import az.fitnest.payment.dto.epoint.*;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.model.entity.CallbackLog;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import az.fitnest.payment.repository.CallbackLogRepository;
import az.fitnest.payment.util.CardBrandDetector;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EpointIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(EpointIntegrationService.class);
    private static final List<String> ALLOWED_CURRENCIES = List.of("AZN", "USD", "EUR");

    private void validatePaymentRequest(Double amount, String currency) {
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive and non-zero");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency must be provided");
        }
        if (!ALLOWED_CURRENCIES.contains(currency.toUpperCase())) {
            throw new IllegalArgumentException("Invalid currency: " + currency);
        }
    }

    private final EpointService epointService;
    private final EpointSigner signer;
    private final EpointProperties epointProperties;
    private final PaymentRepository paymentRepository;
    private final UserCardRepository userCardRepository;
    private final CallbackLogRepository callbackLogRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Transactional
    public EpointResponse initiatePayment(EpointPaymentRequest request, Long userId) {
                        validatePaymentRequest(request.amount(), request.currency());
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
    public EpointResponse cardRegistration(Long userId) {
        EpointCardRegistrationRequest request = EpointCardRegistrationRequest.builder()
            .language("az")
            .refund(0)
            .description("card_save")
            .build();
        String idempotencyKey = generateCardRegistrationKey(userId);
        log.info("cardRegistration: userId={}, idempotencyKey={}", userId, idempotencyKey);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached card registration response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.cardRegistration(request);
        log.info("Epoint cardRegistration response: status={}, message={}, cardId={}", response.status(), response.message(), response.cardId());
        return response;
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
    public EpointResponse refundRequest(EpointRefundRequest request) {
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
        String idempotencyKey = generateIdempotencyKey("split-request", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached split-request response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.splitRequest(request);
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, null);
    }

    @Transactional
    public EpointResponse splitExecutePay(EpointSplitExecutePayRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("split-execute-pay", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached split-execute-pay response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.splitExecutePay(request);
        saveDirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, null);
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, null);
    }

    @Transactional
    public EpointResponse splitCardRegistrationWithPay(EpointSplitPaymentRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("split-card-reg-pay", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached split-card-registration-with-pay response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.splitCardRegistrationWithPay(request);
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, null);
    }

    @Transactional
    public EpointResponse preAuthRequest(EpointPaymentRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("pre-auth-request", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached pre-auth-request response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.preAuthRequest(request);
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, null);
    }

    @Transactional
    public EpointResponse walletPayment(EpointWalletPaymentRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("wallet-payment", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached wallet-payment response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.walletPayment(request);
        saveDirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description());
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, null);
    }

    @Transactional
    public void processCallback(String base64Data, String signature) {
        log.info("[Callback] (ENTRY) processCallback called with base64Data={}, signature={}", base64Data, signature);
        if (base64Data == null || base64Data.isBlank()) {
            log.error("[Callback] Missing base64Data");
            throw new IllegalArgumentException("error.missing_field");
        }
        if (signature == null || signature.isBlank()) {
            log.error("[Callback] Missing signature");
            throw new IllegalArgumentException("error.missing_field");
        }
        boolean verified = signer.verify(base64Data, signature, epointProperties.getPrivateKey());
        log.info("[Callback] Signature verified: {}", verified);
        if (!verified) {
            log.error("[Callback] Signature verification failed");
            throw new SecurityException("error.payment_crypto_error");
        }
        EpointResponse callbackData = signer.decodeData(base64Data, EpointResponse.class);
        log.info("[Callback] Decoded callbackData: {}", callbackData);
        log.info("[Callback] Processing callback for orderId: {}, transaction: {}, status: {}, cardId: {}, cardMask: {}", callbackData.orderId(), callbackData.transaction(), callbackData.status(), callbackData.cardId(), callbackData.cardMask());
        if ((callbackData.orderId() == null || callbackData.orderId().isBlank()) &&
            (callbackData.transaction() == null || callbackData.transaction().isBlank())) {
            log.error("[Callback] Missing orderId and transaction");
            throw new IllegalArgumentException("error.missing_field");
        }
        if (callbackData.status() == null || callbackData.status().isBlank()) {
            log.error("[Callback] Missing status");
            throw new IllegalArgumentException("error.missing_field");
        }
        if (callbackData.amount() != null && callbackData.amount() < 0) {
            log.error("[Callback] Invalid amount: {}", callbackData.amount());
            throw new IllegalArgumentException("error.out_of_range");
        }
        CallbackLog logEntry = CallbackLog.builder()
            .orderId(callbackData.orderId())
            .transactionId(callbackData.transaction())
            .rawJson(base64Data)
            .signature(signature)
            .receivedAt(Instant.now())
            .build();
        callbackLogRepository.save(logEntry);
        try {
            Optional<Payment> optionalPayment = Optional.empty();
            if (callbackData.orderId() != null && !callbackData.orderId().isBlank()) {
                optionalPayment = paymentRepository.findByOrderId(callbackData.orderId());
            }
            if (optionalPayment.isEmpty() && callbackData.transaction() != null && !callbackData.transaction().isBlank()) {
                optionalPayment = paymentRepository.findByTransactionIdForUpdate(callbackData.transaction());
            }
            if (optionalPayment.isPresent()) {
                Payment payment = optionalPayment.get();
                if (Boolean.TRUE.equals(payment.getCallbackProcessed())) {
                    log.warn("[Callback] Callback already processed for orderId: {}, transaction: {}. Skipping duplicate.", callbackData.orderId(), callbackData.transaction());
                    return;
                }
                if (payment.getAmount() != null && callbackData.amount() != null && !payment.getAmount().equals(callbackData.amount())) {
                    log.error("[Callback] Amount mismatch: payment={}, callback={}", payment.getAmount(), callbackData.amount());
                    throw new SecurityException("Amount mismatch");
                }
                updatePaymentFromEpointResponse(payment, callbackData);
                if ((payment.getCurrency() == null || payment.getCurrency().isBlank()) && callbackData.otherAttr() != null && callbackData.otherAttr().get("currency") != null) {
                    String callbackCurrency = String.valueOf(callbackData.otherAttr().get("currency"));
                    if (callbackCurrency != null && !callbackCurrency.isBlank()) {
                        payment.setCurrency(callbackCurrency);
                    }
                }
                payment.setCallbackProcessed(true);
                paymentRepository.save(payment);
                log.info("[Callback] Payment updated from callback. OrderId: {}, Status: {}", callbackData.orderId(), callbackData.status());
                if ("success".equalsIgnoreCase(callbackData.status()) && callbackData.cardId() != null) {
                    Long userId = payment.getUserId();
                    log.info("[Callback] Saving card for userId: {}, cardId: {}", userId, callbackData.cardId());
                    if (userId != null) {
                        upsertCardFromCallback(userId, callbackData);
                        log.info("[Callback] Card attached to user {} from callback. Card ID: {}", userId, callbackData.cardId());
                        List<UserCard> allCards = userCardRepository.findAllByUserId(userId);
                        log.info("[Callback] All cards for user {}: {}", userId, allCards);
                    } else {
                        log.warn("[Callback] Cannot attach card: payment has no userId. OrderId: {}", callbackData.orderId());
                    }
                }
            } else {
                log.warn("[Callback] No payment found for orderId: {} in callback. Creating new record.", callbackData.orderId());
                Payment payment = new Payment();
                payment.setProvider("EPOINT");
                payment.setOrderId(callbackData.orderId());
                payment.setTransactionId(callbackData.transaction());
                payment.setAmount(callbackData.amount());
                String callbackCurrency = null;
                if (callbackData.otherAttr() != null && callbackData.otherAttr().get("currency") != null) {
                    callbackCurrency = String.valueOf(callbackData.otherAttr().get("currency"));
                }
                if (callbackCurrency != null && !callbackCurrency.isBlank()) {
                    payment.setCurrency(callbackCurrency);
                }
                updatePaymentFromEpointResponse(payment, callbackData);
                payment.setCallbackProcessed(true);
                paymentRepository.save(payment);
            }
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            log.warn("[Callback] Concurrent callback detected for orderId: {}. Another thread already processed it. Ignoring.", callbackData.orderId());
        } catch (jakarta.persistence.OptimisticLockException e) {
            log.warn("[Callback] Concurrent callback detected for orderId: {} (JPA). Another thread already processed it. Ignoring.", callbackData.orderId());
        } catch (Exception e) {
            log.error("[Callback] Exception during callback processing", e);
            throw e;
        }
    }

    @Transactional
    public EpointResponse getStatus(String id) {
        String transactionId = paymentRepository.findByOrderId(id)
                .map(Payment::getTransactionId)
                .orElse(id);

        EpointResponse response = epointService.getStatus(transactionId);

        paymentRepository.findByTransactionId(transactionId).ifPresent(payment -> {
            updatePaymentFromEpointResponse(payment, response);
            paymentRepository.save(payment);
        });

        return response;
    }

    @Transactional
    public EpointResponse preAuthComplete(EpointPreAuthCompleteRequest request) {
        return epointService.preAuthComplete(request);
    }
    @Transactional
    public EpointResponse createWidgetUrl(EpointWidgetRequest request) {
        return epointService.createWidgetUrl(request);
    }
    @Transactional
    public EpointResponse walletStatus() {
        return epointService.walletStatus();
    }
    @Transactional
    public EpointResponse createInvoice(EpointInvoiceCreateRequest request) {
        return epointService.createInvoice(request);
    }
    @Transactional
    public EpointResponse updateInvoice(EpointInvoiceUpdateRequest request) {
        return epointService.updateInvoice(request);
    }
    @Transactional
    public EpointResponse viewInvoice(Long id) {
        return epointService.viewInvoice(id);
    }
    @Transactional
    public EpointResponse listInvoices(String type, String order) {
        return epointService.listInvoices(type, order);
    }
    @Transactional
    public EpointResponse sendInvoiceSms(Long id, String phone) {
        return epointService.sendInvoiceSms(id, phone);
    }
    @Transactional
    public EpointResponse sendInvoiceEmail(Long id, String email) {
        return epointService.sendInvoiceEmail(id, email);
    }
    @Transactional
    public EpointResponse heartbeat() {
        return epointService.heartbeat();
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
            payment.setType("PAYMENT");
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
        payment.setType("PAYMENT");
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
                .bankResponse(payment.getBankResponse())
                .operationCode(payment.getOperationCode())
                .rrn(payment.getRrn())
                .cardName(payment.getCardName())
                .cardMask(payment.getCardMask())
                .amount(payment.getAmount())
                .splitAmount(null)
                .cardId(payment.getCardId())
                .widgetUrl(null)
                .message(payment.getMessage())
                .code(payment.getCode())
                .otherAttr(null)
                .build();
    }

    private String mapInternalStatusToExternalStatus(String internalStatus) {
        if (internalStatus == null) {
            return null;
        }

        switch (internalStatus.toUpperCase()) {
            case "PENDING_USER_ACTION":
                return "new";

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
        log.info("[CardSave] (ENTRY) upsertCardFromCallback: userId={}, cardId={}, cardMask={}, cardName={}, callbackData={}", userId, callbackData.cardId(), callbackData.cardMask(), callbackData.cardName(), callbackData);

        if (callbackData.cardId() == null || callbackData.cardId().isBlank()) {
            log.warn("[CardSave] No cardId in callbackData, skipping card save.");
            return;
        }

        Optional<UserCard> existingCard = userCardRepository.findByUserIdAndCardId(userId, callbackData.cardId());

        if (existingCard.isPresent()) {
            UserCard card = existingCard.get();
            if (!card.getCardMask().equals(callbackData.cardMask())) {
                log.warn("[CardSave] Card mask mismatch for cardId {}: existing={}, new={}. Skipping update.", callbackData.cardId(), card.getCardMask(), callbackData.cardMask());
                return;
            }
            card.setCardName(callbackData.cardName());
            card.setBankTransaction(callbackData.bankTransaction());
            card.setBankResponse(callbackData.bankResponse());
            card.setOperationCode(callbackData.operationCode());
            card.setRrn(callbackData.rrn());
            userCardRepository.save(card);
            log.info("[CardSave] Updated existing card {} for user {}", callbackData.cardId(), userId);
        } else {
            boolean duplicateCard = userCardRepository.findAllByUserId(userId).stream()
                .anyMatch(card -> card.getCardMask().equals(callbackData.cardMask()) && card.getCardName().equals(callbackData.cardName()));

            if (duplicateCard) {
                log.warn("[CardSave] Duplicate card detected for user {} with cardMask {}. Skipping save.", userId, callbackData.cardMask());
                return;
            }

            UserCard userCard = UserCard.builder()
                    .userId(userId)
                    .cardId(callbackData.cardId())
                    .cardMask(callbackData.cardMask())
                    .cardName(callbackData.cardName())
                    .bankTransaction(callbackData.bankTransaction())
                    .bankResponse(callbackData.bankResponse())
                    .operationCode(callbackData.operationCode())
                    .rrn(callbackData.rrn())
                    .build();
            userCardRepository.save(userCard);
            log.info("[CardSave] Created new card {} for user {}", callbackData.cardId(), userId);
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

    public String getSuccessRedirectUrl() {
        return epointProperties.getSuccessRedirectUrl();
    }

    public String getErrorRedirectUrl() {
        return epointProperties.getErrorRedirectUrl();
    }

    public String getResultCallbackUrl() {
        return epointProperties.getResultUrl();
    }

    @Transactional
    public EpointResponse initiatePayment(Double amount, String currency, Long userId) {
        String orderId = java.util.UUID.randomUUID().toString();
        EpointPaymentRequest request = EpointPaymentRequest.builder()
                .currency(currency != null ? currency : "AZN")
                .amount(amount)
                .language("az")
                .orderId(orderId)
                .description("Fitness package payment")
                .isInstallment(0)
                .refund(0)
                .build();
        return initiatePayment(request, userId);
    }

    @Transactional
    public EpointResponse executePay(Double amount, String currency, String cardId, Long userId) {
                        validatePaymentRequest(amount, currency);
        String orderId = java.util.UUID.randomUUID().toString();
        EpointExecutePayRequest request = EpointExecutePayRequest.builder()
                .language("az")
                .orderId(orderId)
                .amount(amount)
                .currency(currency)
                .cardId(cardId)
                .isInstallment(0)
                .build();
        return executePay(request, userId);
    }

    @Transactional
    public EpointResponse cardRegistrationWithPay(Double amount, String currency, Long userId) {
                        validatePaymentRequest(amount, currency);
        String orderId = java.util.UUID.randomUUID().toString();
        EpointPaymentRequest request = EpointPaymentRequest.builder()
                .currency(currency != null ? currency : "AZN")
                .amount(amount)
                .language("az")
                .orderId(orderId)
                .description("Fitness package payment")
                .isInstallment(0)
                .refund(0)
                .build();
        return cardRegistrationWithPay(userId, request);
    }

    @Transactional
    public EpointResponse splitRequest(Double amount, String currency, String splitUser, Double splitAmount, Long userId) {
                        validatePaymentRequest(amount, currency);
        String orderId = java.util.UUID.randomUUID().toString();
        EpointSplitPaymentRequest request = EpointSplitPaymentRequest.builder()
                .language("az")
                .orderId(orderId)
                .amount(amount)
                .currency(currency)
                .splitUser(splitUser)
                .splitAmount(splitAmount)
                .build();
        return splitRequest(request, userId);
    }

    @Transactional
    public EpointResponse splitExecutePay(Double amount, String currency, String cardId, String splitUser, Double splitAmount, Long userId) {
                        validatePaymentRequest(amount, currency);
                        validatePaymentRequest(amount, currency);
        String orderId = java.util.UUID.randomUUID().toString();
        EpointSplitExecutePayRequest request = EpointSplitExecutePayRequest.builder()
                .language("az")
                .orderId(orderId)
                .amount(amount)
                .currency(currency)
                .cardId(cardId)
                .splitUser(splitUser)
                .splitAmount(splitAmount)
                .build();
        return splitExecutePay(request, userId);
    }

    @Transactional
    public EpointResponse splitCardRegistrationWithPay(Double amount, String currency, String splitUser, Double splitAmount, Long userId) {
        String orderId = java.util.UUID.randomUUID().toString();
        EpointSplitPaymentRequest request = EpointSplitPaymentRequest.builder()
                .language("az")
                .orderId(orderId)
                .amount(amount)
                .currency(currency)
                .splitUser(splitUser)
                .splitAmount(splitAmount)
                .build();
        return splitCardRegistrationWithPay(request, userId);
    }
}
