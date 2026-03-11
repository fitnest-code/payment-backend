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
    private final CallbackLogRepository callbackLogRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

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
        log.info("cardRegistration: userId={}, idempotencyKey={}, request.publicKey={}, epointProperties.publicKey={}, env.EPOINT_PUBLIC_KEY={}", userId, idempotencyKey, request.publicKey(), epointProperties.getPublicKey(), System.getenv("EPOINT_PUBLIC_KEY"));
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached card registration response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.cardRegistration(request);
        log.info("Epoint cardRegistration response: status={}, message={}, cardId={}", response.status(), response.message(), response.cardId());
        if ("success".equalsIgnoreCase(response.status()) && response.cardId() != null) {
            Payment tracking = new Payment();
            tracking.setProvider("EPOINT");
            tracking.setStatus("PENDING_USER_ACTION");
            tracking.setUserId(userId);
            tracking.setDescription("card-registration");
            tracking.setRedirectUrl(response.redirectUrl());
            tracking.setCardMask(response.cardMask());
            tracking.setCardName(response.cardName());
            tracking.setCardId(response.cardId());
            paymentRepository.save(tracking);
            log.info("Created card-registration tracking record. Card ID: {}, UserId: {}", response.cardId(), userId);
        }
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
        log.info("[Callback] Received callback: base64Data={}, signature={}", base64Data, signature);
        if (base64Data == null || base64Data.isBlank()) {
            log.error("[Callback] Missing base64Data");
            throw new IllegalArgumentException("error.missing_field");
        }
        if (signature == null || signature.isBlank()) {
            log.error("[Callback] Missing signature");
            throw new IllegalArgumentException("error.missing_field");
        }

        if (!signer.verify(base64Data, signature, epointProperties.getPrivateKey())) {
            log.error("[Callback] Signature verification failed for data: {}", base64Data);
            throw new SecurityException("error.payment_crypto_error");
        }

        EpointResponse callbackData = signer.decodeData(base64Data, EpointResponse.class);
        log.info("[Callback] Decoded callbackData: orderId={}, transaction={}, status={}, cardId={}, cardMask={}",
            callbackData.orderId(), callbackData.transaction(), callbackData.status(), callbackData.cardId(), callbackData.cardMask());

        if ((callbackData.orderId() == null || callbackData.orderId().isBlank()) &&
            (callbackData.transaction() == null || callbackData.transaction().isBlank())) {
            throw new IllegalArgumentException("error.missing_field");
        }
        if (callbackData.status() == null || callbackData.status().isBlank()) {
            throw new IllegalArgumentException("error.invalid_field");
        }
        if (callbackData.amount() != null && callbackData.amount() < 0) {
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
                optionalPayment = paymentRepository.findByTransactionId(callbackData.transaction());
            }

            if (optionalPayment.isPresent()) {
                Payment payment = optionalPayment.get();

                if (Boolean.TRUE.equals(payment.getCallbackProcessed())) {
                    log.warn("[Callback] Callback already processed for orderId: {}, transaction: {}. Skipping duplicate.",
                        callbackData.orderId(), callbackData.transaction());
                    return;
                }

                updatePaymentFromEpointResponse(payment, callbackData);
                payment.setCallbackProcessed(true);

                paymentRepository.save(payment);

                if ("success".equalsIgnoreCase(callbackData.status()) && callbackData.cardId() != null) {
                    Long userId = payment.getUserId();
                    if (userId != null) {
                        log.info("[Callback] Attaching card to user: {}. cardId={}, cardMask={}, cardName={}", userId, callbackData.cardId(), callbackData.cardMask(), callbackData.cardName());
                        upsertCardFromCallback(userId, callbackData);
                        log.info("[Callback] Card attached to user {} from callback. Card ID: {}", userId, callbackData.cardId());
                    } else {
                        log.warn("[Callback] Cannot attach card: payment has no userId. OrderId: {}", callbackData.orderId());
                    }
                }

                log.info("[Callback] Payment updated from callback. OrderId: {}, Status: {}", callbackData.orderId(), callbackData.status());
            } else {
                log.warn("[Callback] No payment found for orderId: {} in callback. Creating new record.", callbackData.orderId());
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
            log.warn("[Callback] Concurrent callback detected for orderId: {}. Another thread already processed it. Ignoring.",
                callbackData.orderId());
        } catch (jakarta.persistence.OptimisticLockException e) {
            log.warn("[Callback] Concurrent callback detected for orderId: {} (JPA). Another thread already processed it. Ignoring.",
                callbackData.orderId());
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
        log.info("[Callback] upsertCardFromCallback: userId={}, cardId={}, cardMask={}, cardName={}, brand={}",
            userId, callbackData.cardId(), callbackData.cardMask(), callbackData.cardName(), CardBrandDetector.detectBrand(callbackData.cardMask()));
        if (callbackData.cardId() == null || callbackData.cardId().isBlank()) {
            log.warn("[Callback] No cardId in callback, skipping card upsert.");
            return;
        }

        Optional<UserCard> existingCard = userCardRepository.findByUserIdAndCardId(userId, callbackData.cardId());

        if (existingCard.isPresent()) {
            UserCard card = existingCard.get();
            log.info("[Callback] Updating existing card: id={}, userId={}, cardId={}, cardMask={}, cardName={}, brand={}",
                card.getId(), card.getUserId(), card.getCardId(), callbackData.cardMask(), callbackData.cardName(), CardBrandDetector.detectBrand(callbackData.cardMask()));
            card.setCardMask(callbackData.cardMask());
            card.setCardName(callbackData.cardName());
            if (callbackData.cardMask() != null) {
                card.setBrand(CardBrandDetector.detectBrand(callbackData.cardMask()));
            }
            userCardRepository.save(card);
            log.info("[Callback] Updated existing card {} for user {}", callbackData.cardId(), userId);
        } else {
            boolean isFirstCard = userCardRepository.findAllByUserId(userId).isEmpty();
            log.info("[Callback] Creating new card for user: {}. cardId={}, cardMask={}, cardName={}, brand={}, isDefault={}",
                userId, callbackData.cardId(), callbackData.cardMask(), callbackData.cardName(), CardBrandDetector.detectBrand(callbackData.cardMask()), isFirstCard);
            UserCard userCard = UserCard.builder()
                    .userId(userId)
                    .cardId(callbackData.cardId())
                    .cardMask(callbackData.cardMask())
                    .cardName(callbackData.cardName())
                    .brand(CardBrandDetector.detectBrand(callbackData.cardMask()))
                    .isDefault(isFirstCard)
                    .build();
            userCardRepository.save(userCard);
            log.info("[Callback] Created new card {} for user {}", callbackData.cardId(), userId);
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
