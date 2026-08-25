package az.fitnest.payment.service;

import az.fitnest.payment.client.epoint.EpointService;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.client.epoint.EpointProperties;
import az.fitnest.payment.dto.epoint.EpointResponse;
import az.fitnest.payment.dto.epoint.*;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.model.entity.CallbackLog;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
import az.fitnest.payment.repository.CallbackLogRepository;
import az.fitnest.payment.util.CardBrandDetector;
import az.fitnest.payment.util.CardMaskUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import az.fitnest.payment.dto.common.GooglePaySubmitRequest;
import az.fitnest.payment.dto.common.GooglePaySubmitResponse;
import az.fitnest.payment.dto.common.ApplePaySubmitRequest;
import az.fitnest.payment.dto.common.ApplePaySubmitResponse;
import az.fitnest.payment.client.UserGrpcClient;
import az.fitnest.payment.client.epoint.EpointHttpClient;
import az.fitnest.payment.util.PaymentPackageRef;

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
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final az.fitnest.payment.client.SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final PaymentSubscriptionService paymentSubscriptionService;
    private final UserGrpcClient userGrpcClient;
    private final EpointHttpClient httpClient;

    public EpointResponse initiatePayment(EpointPaymentRequest request, Long userId) {
        log.info("[PaymentInit] (SERVICE ENTRY) userId={}, orderId={}, amount={}, currency={}, description={}, otherAttr={}, publicKey={}",
                userId, request.orderId(), request.amount(), request.currency(), request.description(), request.otherAttr(), request.publicKey());
        try {
            validatePaymentRequest(request.amount(), request.currency());
            String idempotencyKey = generateIdempotencyKey("payment", request.orderId(), userId);
            Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
            if (cachedResponse.isPresent()) {
                log.info("[PaymentInit] (SERVICE) Returning cached response for idempotency key: {}", idempotencyKey);
                return cachedResponse.get();
            }
            Optional<Payment> existingPayment = paymentRepository.findByOrderId(request.orderId());
            if (existingPayment.isPresent()) {
                log.warn("[PaymentInit] (SERVICE) Payment with orderId {} already exists", request.orderId());
                Payment payment = existingPayment.get();
                EpointResponse response = buildResponseFromPayment(payment);
                return idempotencyService.persistIdempotentResponse(idempotencyKey, response, payment);
            }
            log.info("[PaymentInit] (SERVICE) Creating payment via epointService.createPayment. orderId={}, userId={}", request.orderId(), userId);
            EpointResponse response = epointService.createPayment(request);
            log.info("[PaymentInit] (SERVICE) Payment created. status={}, message={}, transaction={}, orderId={}", response.status(), response.message(), response.transaction(), response.orderId());
            Payment payment = saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), request.autoPaymentEnabled());
            if (userId != null && request.orderId() != null) {
                String redisKey = "payment-user:" + request.orderId();
                redisTemplate.opsForValue().set(redisKey, String.valueOf(userId), 1, java.util.concurrent.TimeUnit.DAYS);
            }
            log.info("[PaymentInit] (SERVICE) Payment entity saved. paymentId={}, userId={}, orderId={}", payment != null ? payment.getId() : null, userId, request.orderId());
            return idempotencyService.persistIdempotentResponse(idempotencyKey, response.withOrderId(request.orderId()), payment);
        } catch (Exception e) {
            log.error("[PaymentInit] (SERVICE ERROR) Exception occurred: {}", e.getMessage(), e);
            throw e;
        }
    }

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
        String registrationId = java.util.UUID.randomUUID().toString();
        EpointResponse response = epointService.cardRegistration(request, registrationId);
        log.info("Epoint cardRegistration response: status={}, message={}, cardId={}, transaction={}",
                response.status(), response.message(), response.cardId(), response.transaction());
        
        if (response.transaction() != null && !response.transaction().isBlank()) {
            String regTxKey = "registration-tx:" + registrationId;
            redisTemplate.opsForValue().set(regTxKey, response.transaction(), 1, java.util.concurrent.TimeUnit.HOURS);
            log.info("Cached registration transaction mapping: {} -> {}", regTxKey, response.transaction());
        }

        if ("success".equalsIgnoreCase(response.status()) && response.cardId() != null) {
            String redisKey = "card-reg:" + response.cardId();
            redisTemplate.opsForValue().set(redisKey, String.valueOf(userId), 30, java.util.concurrent.TimeUnit.MINUTES);
            log.info("Stored card registration mapping cardId={} -> userId={}", response.cardId(), userId);
        }
        return response;
    }

    public EpointResponse executePay(EpointExecutePayRequest request, Long userId) {
        // successRedirectUrl / errorRedirectUrl are resolved dynamically in EpointService.fillPublicKey
        // based on the incoming request Origin/Referer headers, so no manual override is needed here.
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
        Payment payment = saveDirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), request.autoPaymentEnabled());
        if ("success".equalsIgnoreCase(response.status()) && payment != null) {
            assignSubscriptionIfPossible(payment, response, userId);
        }
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response.withOrderId(request.orderId()), payment);
    }

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
        Payment payment = saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), request.autoPaymentEnabled());
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response.withOrderId(request.orderId()), payment);
    }

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

    public EpointResponse splitRequest(EpointSplitPaymentRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("split-request", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached split-request response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.splitRequest(request);
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), false);
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, null);
    }

    public EpointResponse splitExecutePay(EpointSplitExecutePayRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("split-execute-pay", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached split-execute-pay response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.splitExecutePay(request);
        saveDirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, null, false);
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, null);
    }

    public EpointResponse splitCardRegistrationWithPay(EpointSplitPaymentRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("split-card-reg-pay", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached split-card-registration-with-pay response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.splitCardRegistrationWithPay(request);
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), false);
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, null);
    }

    public EpointResponse preAuthRequest(EpointPaymentRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("pre-auth-request", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached pre-auth-request response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.preAuthRequest(request);
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), false);
        return idempotencyService.persistIdempotentResponse(idempotencyKey, response, null);
    }

    public EpointResponse walletPayment(EpointWalletPaymentRequest request, Long userId) {
        String idempotencyKey = generateIdempotencyKey("wallet-payment", request.orderId(), userId);
        Optional<EpointResponse> cachedResponse = idempotencyService.getCachedResponse(idempotencyKey);
        if (cachedResponse.isPresent()) {
            log.info("Returning cached wallet-payment response for idempotency key: {}", idempotencyKey);
            return cachedResponse.get();
        }
        EpointResponse response = epointService.walletPayment(request);
        saveDirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), false);
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

        try {
            CallbackLog logEntity = CallbackLog.builder()
                    .orderId(callbackData != null ? callbackData.orderId() : null)
                    .transactionId(callbackData != null ? callbackData.transaction() : null)
                    .rawJson(objectMapper.writeValueAsString(callbackData))
                    .signature(signature)
                    .receivedAt(java.time.Instant.now())
                    .build();
            callbackLogRepository.save(logEntity);
            log.info("[Callback] Saved callback log entity ID: {}", logEntity.getId());
        } catch (Exception e) {
            log.warn("[Callback] Failed to save callback log: {}", e.getMessage());
        }

        log.info("[Callback] Processing callback for bankTransaction: {}, bankResponse: {}, rrn: {}, approvalCode: {}",
                callbackData.bankTransaction(), callbackData.bankResponse(), callbackData.rrn(), callbackData.approvalCode());

        if (callbackData.bankTransaction() == null || callbackData.bankTransaction().isBlank()) {
            log.warn("[Callback] Missing bankTransaction for callbackData: {}", callbackData);
        }

        if ("success".equalsIgnoreCase(callbackData.status())
                && (callbackData.rrn() == null || callbackData.rrn().isBlank())) {
            log.warn("[Callback] Missing rrn for successful callback orderId={}", callbackData.orderId());
        }

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
                if (payment.getUserId() == null && callbackData.orderId() != null && !callbackData.orderId().isBlank()) {
                    String redisKey = "payment-user:" + callbackData.orderId();
                    String userIdStr = redisTemplate.opsForValue().get(redisKey);
                    if (userIdStr != null) {
                        try {
                            Long userId = Long.parseLong(userIdStr);
                            payment.setUserId(userId);
                            log.info("[Callback] Set userId from Redis for orderId={}, userId={}", callbackData.orderId(), userId);
                        } catch (NumberFormatException e) {
                            log.warn("[Callback] Invalid userId in Redis for orderId={}: {}", callbackData.orderId(), userIdStr);
                        }
                    }
                }
                updatePaymentFromEpointResponse(payment, callbackData);
                if ((payment.getCurrency() == null || payment.getCurrency().isBlank()) && callbackData.otherAttr() != null) {
                    String callbackCurrency = getOtherAttrValue(callbackData.otherAttr(), "currency");
                    if (callbackCurrency != null && !callbackCurrency.isBlank()) {
                        payment.setCurrency(callbackCurrency);
                    }
                }
                payment.setCallbackProcessed(true);
                paymentRepository.save(payment);
                log.info("[Callback] Payment updated from callback. OrderId: {}, Status: {}", callbackData.orderId(), callbackData.status());

                if ("success".equalsIgnoreCase(callbackData.status())) {
                    Long userId = payment.getUserId();
                    if (callbackData.cardId() != null && userId != null) {
                        log.info("[Callback] Saving card for userId: {}, cardId: {}", userId, callbackData.cardId());
                        upsertCardFromCallback(userId, callbackData);
                        log.info("[Callback] Card attached to user {} from callback. Card ID: {}", userId, callbackData.cardId());
                        List<UserCard> allCards = userCardRepository.findAllByUserId(userId);
                        log.info("[Callback] All cards for user {}: {}", userId, allCards);
                    }
                    assignSubscriptionIfPossible(payment, callbackData, payment.getUserId());
                }
            } else {
                if (callbackData.cardId() != null && !callbackData.cardId().isBlank()
                        && "success".equalsIgnoreCase(callbackData.status())) {
                    String redisKey = "card-reg:" + callbackData.cardId();
                    String userIdStr = redisTemplate.opsForValue().get(redisKey);
                    if (userIdStr != null) {
                        Long userId = Long.parseLong(userIdStr);
                        log.info("[Callback] Card-registration callback for cardId={}, userId={}", callbackData.cardId(), userId);
                        upsertCardFromCallback(userId, callbackData);
                        redisTemplate.delete(redisKey);
                        log.info("[Callback] Card saved for userId={}, cardId={}", userId, callbackData.cardId());
                    } else {
                        log.warn("[Callback] No userId found in Redis for cardId={}. Card not saved.", callbackData.cardId());
                    }
                }
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

    public EpointResponse getStatus(String id) {
        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(id)
                .or(() -> paymentRepository.findByTransactionId(id));

        String queryId = paymentOpt.map(Payment::getTransactionId).orElse(id);
        if (queryId == null || queryId.isBlank()) {
            queryId = id;
        }

        EpointResponse response = epointService.getStatus(queryId);

        if (paymentOpt.isPresent()) {
            Payment payment = paymentOpt.get();
            String oldStatus = payment.getStatus();
            updatePaymentFromEpointResponse(payment, response);
            
            if ("SUCCESS".equalsIgnoreCase(payment.getStatus()) && !"SUCCESS".equalsIgnoreCase(oldStatus)) {
                payment.setCallbackProcessed(true);
                paymentRepository.save(payment);
                assignSubscriptionIfPossible(payment, response, payment.getUserId());
            } else {
                paymentRepository.save(payment);
            }
        }

        return response;
    }

    public EpointResponse preAuthComplete(EpointPreAuthCompleteRequest request) {
        return epointService.preAuthComplete(request);
    }

    public EpointResponse createWidgetUrl(Long userId, Long packageId, Long optionId, Boolean autoPaymentEnabled) {
        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
        Double amount = priceCurrency.amount;
        String currency = priceCurrency.currency;

        validatePaymentRequest(amount, currency);

        String orderId = java.util.UUID.randomUUID().toString();
        String deviceType = az.fitnest.payment.util.DeviceDetector.detectDeviceType();
        String paymentTypeDescription = Boolean.TRUE.equals(autoPaymentEnabled) ? "Monthly payment" : "One-time payment";
        String description = PaymentPackageRef.encode(packageId, optionId)
                + ",device:" + deviceType + ",type:" + paymentTypeDescription;

        if (userId != null) {
            String redisKey = "payment-user:" + orderId;
            redisTemplate.opsForValue().set(redisKey, String.valueOf(userId), 1, java.util.concurrent.TimeUnit.DAYS);
            log.info("[WidgetUrl] Stored userId mapping in Redis for orderId: {}", orderId);
        }

        EpointWidgetRequest request = EpointWidgetRequest.builder()
                .amount(amount)
                .currency(currency != null ? currency : "AZN")
                .orderId(orderId)
                .description(description)
                .autoPaymentEnabled(autoPaymentEnabled)
                .build();

        log.info("[WidgetUrl] (SERVICE) Calling Epoint widget API. userId={}, orderId={}, amount={}", userId, orderId, amount);

        EpointResponse response = epointService.createWidgetUrl(request);

        if ("success".equalsIgnoreCase(response.status())) {
            String widgetUrl = response.widgetUrl();
            String transactionId = response.transaction();
            if (widgetUrl != null && !widgetUrl.isBlank()) {
                int widgetIdx = widgetUrl.indexOf("/widget/");
                if (widgetIdx != -1) {
                    String token = widgetUrl.substring(widgetIdx + "/widget/".length()).trim();
                    int questionIdx = token.indexOf('?');
                    if (questionIdx != -1) {
                        token = token.substring(0, questionIdx);
                    }
                    int hashIdx = token.indexOf('#');
                    if (hashIdx != -1) {
                        token = token.substring(0, hashIdx);
                    }
                    if (token.endsWith("/")) {
                        token = token.substring(0, token.length() - 1);
                    }
                    if (!token.isBlank() && token.matches("\\d+")) {
                        try {
                            String paddedToken = String.format("%010d", Long.parseLong(token));
                            transactionId = "tw" + paddedToken;
                            log.info("[WidgetUrl] Extracted widget token: {}, formatted transactionId: {}", token, transactionId);
                        } catch (Exception e) {
                            log.warn("[WidgetUrl] Failed to format widget token: {}", token, e);
                        }
                    }
                }
            }

            Payment payment = new Payment();
            payment.setProvider("EPOINT");
            payment.setOrderId(orderId);
            payment.setTransactionId(transactionId);
            payment.setAmount(amount);
            payment.setCurrency(currency);
            payment.setStatus("PENDING_USER_ACTION");
            payment.setUserId(userId);
            payment.setDescription(description);
            payment.setType("WIDGET_PAYMENT");
            payment.setAutoPaymentEnabled(autoPaymentEnabled != null ? autoPaymentEnabled : false);
            payment.setRedirectUrl(widgetUrl);
            paymentRepository.save(payment);
            log.info("[WidgetUrl] (SERVICE) Pending payment saved. orderId={}, transactionId={}", orderId, transactionId);

            return EpointResponse.builder()
                    .status(response.status())
                    .transaction(transactionId)
                    .orderId(orderId)
                    .widgetUrl(widgetUrl)
                    .message(response.message())
                    .code(response.code())
                    .build();
        }

        return response;
    }

    public EpointResponse createWidgetUrl(EpointWidgetRequest request) {
        return epointService.createWidgetUrl(request);
    }
    public EpointResponse walletStatus() {
        return epointService.walletStatus();
    }
    public EpointResponse createInvoice(EpointInvoiceCreateRequest request) {
        return epointService.createInvoice(request);
    }
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
    public EpointResponse heartbeat() {
        return epointService.heartbeat();
    }

    private void updatePaymentFromEpointResponse(Payment payment, EpointResponse response) {
        String newStatus = response.status() != null ? response.status().toUpperCase() : payment.getStatus();

        if (("FAILED".equalsIgnoreCase(newStatus) || "ERROR".equalsIgnoreCase(newStatus) || "SERVER_ERROR".equalsIgnoreCase(newStatus))
                && ("PENDING".equals(payment.getStatus()) || "PENDING_USER_ACTION".equals(payment.getStatus()) || "PENDING_3DS".equals(payment.getStatus()) || "NEW".equals(payment.getStatus()))) {

            boolean hasAttempt = (response.cardMask() != null && !response.cardMask().isBlank())
                    || (response.bankTransaction() != null && !response.bankTransaction().isBlank())
                    || (response.bankResponse() != null && !response.bankResponse().isBlank())
                    || (response.code() != null && !response.code().isBlank() && !"500".equals(response.code()) && !"ERROR".equalsIgnoreCase(response.code()));

            if (!hasAttempt) {
                java.time.Instant thirtyMinutesAgo = java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.MINUTES);
                if (payment.getCreatedDate() != null && payment.getCreatedDate().atZone(java.time.ZoneId.systemDefault()).toInstant().isBefore(thirtyMinutesAgo)) {
                    log.info("[StatusSync] Epoint returned error/failed status and no attempt, but payment is older than 30 minutes. Marking as FAILED.");
                    newStatus = "FAILED";
                } else {
                    log.info("[StatusSync] Epoint returned error/failed/server_error status but no transaction attempt detected. Retaining pending status: {}", payment.getStatus());
                    newStatus = payment.getStatus();
                }
            }
        }

        payment.setStatus(newStatus);
        payment.setTransactionId(response.transaction() != null ? response.transaction() : payment.getTransactionId());
        payment.setBankTransaction(response.bankTransaction());
        payment.setRrn(response.rrn());
        payment.setCardMask(CardMaskUtil.toLast4(response.cardMask()));
        payment.setCardName(response.cardName());
        payment.setMessage(response.message());
        payment.setCode(response.code());
        payment.setBankResponse(response.bankResponse());
        payment.setOperationCode(response.operationCode());
    }

    private Payment saveRedirectPayment(EpointResponse response, String orderId, Double amount, String currency, Long userId, String description, Boolean autoPaymentEnabled) {
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
            payment.setCardMask(CardMaskUtil.toLast4(response.cardMask()));
            payment.setCardName(response.cardName());
            payment.setRrn(response.rrn());
            payment.setBankTransaction(response.bankTransaction());
            payment.setMessage(response.message());
            payment.setType("PAYMENT");
            payment.setAutoPaymentEnabled(autoPaymentEnabled != null ? autoPaymentEnabled : false);
            return paymentRepository.save(payment);
        }
        return null;
    }

    private Payment saveRedirectPayment(EpointResponse response, String orderId, Double amount, String currency) {
        return saveRedirectPayment(response, orderId, amount, currency, null, null, false);
    }

    private Payment saveDirectPayment(EpointResponse response, String orderId, Double amount, String currency, Long userId, String description, Boolean autoPaymentEnabled) {
        Payment payment = new Payment();
        payment.setProvider("EPOINT");
        payment.setOrderId(orderId);
        payment.setTransactionId(response.transaction());
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setStatus(response.status() != null ? response.status().toUpperCase() : "UNKNOWN");
        payment.setUserId(userId);
        payment.setDescription(description);
        payment.setCardMask(CardMaskUtil.toLast4(response.cardMask()));
        payment.setCardName(response.cardName());
        payment.setRrn(response.rrn());
        payment.setBankTransaction(response.bankTransaction());
        payment.setBankResponse(response.bankResponse());
        payment.setOperationCode(response.operationCode());
        payment.setCode(response.code());
        payment.setMessage(response.message());
        payment.setCallbackProcessed(true);
        payment.setType("PAYMENT");
        payment.setAutoPaymentEnabled(autoPaymentEnabled != null ? autoPaymentEnabled : false);
        return paymentRepository.save(payment);
    }

    private Payment saveDirectPayment(EpointResponse response, String orderId, Double amount, String currency) {
        return saveDirectPayment(response, orderId, amount, currency, null, null, false);
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
                .cardMask(CardMaskUtil.toLast4(payment.getCardMask()))
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

        java.util.Map<String, String> bankRespMap = new java.util.HashMap<>();
        if (callbackData.bankResponse() != null) {
            String[] lines = callbackData.bankResponse().split("\\n");
            for (String line : lines) {
                int idx = line.indexOf(":");
                if (idx > 0) {
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    bankRespMap.put(key, value);
                }
            }
        }

        java.util.function.BiFunction<String, String, String> getField = (direct, key) -> {
            if (direct != null && !direct.isBlank()) return direct;
            return bankRespMap.getOrDefault(key, null);
        };

        Optional<UserCard> existingCard = userCardRepository.findByUserIdAndCardId(userId, callbackData.cardId());

        if (existingCard.isPresent()) {
            UserCard card = existingCard.get();
            String existingMask = CardMaskUtil.toLast4(card.getCardMask());
            String incomingMask = CardMaskUtil.toLast4(callbackData.cardMask());
            if (incomingMask != null && existingMask != null && !existingMask.equals(incomingMask)) {
                log.warn("[CardSave] Card mask mismatch for cardId {}: existing={}, new={}. Skipping update.",
                        callbackData.cardId(), existingMask, incomingMask);
                return;
            }
            card.setCardName(getField.apply(callbackData.cardName(), "CARDNAME"));
            card.setBankTransaction(callbackData.bankTransaction());
            card.setOperationCode(callbackData.operationCode());
            card.setRrn(getField.apply(callbackData.rrn(), "RRN"));
            card.setApprovalCode(getField.apply(callbackData.approvalCode(), "APPROVAL_CODE"));
            card.setCardNumber(CardMaskUtil.toLast4(getField.apply(callbackData.cardNumber(), "CARD_NUMBER")));
            card.setReccPmntId(getField.apply(callbackData.reccPmntId(), "RECC_PMNT_ID"));
            if (incomingMask != null && !incomingMask.isBlank()) {
                card.setCardMask(incomingMask);
            }
            userCardRepository.save(card);
            log.info("[CardSave] Updated existing card {} for user {}", callbackData.cardId(), userId);
        } else {
            String incomingMask = CardMaskUtil.toLast4(callbackData.cardMask());
            boolean duplicateCard = userCardRepository.findAllByUserId(userId).stream()
                    .anyMatch(card -> {
                        String stored = CardMaskUtil.toLast4(card.getCardMask());
                        return stored != null && stored.equals(incomingMask)
                                && java.util.Objects.equals(card.getCardName(), callbackData.cardName());
                    });

            if (duplicateCard) {
                log.warn("[CardSave] Duplicate card detected for user {} with cardMask {}. Skipping save.", userId, incomingMask);
                return;
            }

            UserCard userCard = UserCard.builder()
                    .userId(userId)
                    .cardId(callbackData.cardId())
                    .cardMask(incomingMask != null ? incomingMask : CardMaskUtil.EMPTY_DISPLAY)
                    .cardName(getField.apply(callbackData.cardName(), "CARDNAME"))
                    .brand(CardBrandDetector.detectBrand(callbackData.cardMask()))
                    .bankTransaction(callbackData.bankTransaction())
                    .operationCode(callbackData.operationCode())
                    .rrn(getField.apply(callbackData.rrn(), "RRN"))
                    .approvalCode(getField.apply(callbackData.approvalCode(), "APPROVAL_CODE"))
                    .cardNumber(CardMaskUtil.toLast4(getField.apply(callbackData.cardNumber(), "CARD_NUMBER")))
                    .reccPmntId(getField.apply(callbackData.reccPmntId(), "RECC_PMNT_ID"))
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

    public String getSuccessRedirectUrl(String id) {
        String redisKey = "payment-redirect:success:" + id;
        String targetUrl = redisTemplate.opsForValue().get(redisKey);
        if (targetUrl != null) {
            redisTemplate.delete(redisKey);
            log.info("[Redirection] Found cached success URL for key {}: {}", redisKey, targetUrl);
            return targetUrl;
        }
        log.warn("[Redirection] No cached success URL for key {}, falling back to default", redisKey);
        return epointProperties.getSuccessRedirectUrl();
    }

    public String getErrorRedirectUrl() {
        return epointProperties.getErrorRedirectUrl();
    }

    public String getErrorRedirectUrl(String id) {
        String redisKey = "payment-redirect:error:" + id;
        String targetUrl = redisTemplate.opsForValue().get(redisKey);
        if (targetUrl == null) {
            targetUrl = epointProperties.getErrorRedirectUrl();
        } else {
            redisTemplate.delete(redisKey);
        }

        String transactionId = null;
        String bankCode = null;

        // Try to find in database by orderId or transactionId
        Optional<Payment> optionalPayment = paymentRepository.findByOrderId(id);
        if (optionalPayment.isEmpty()) {
            optionalPayment = paymentRepository.findByTransactionId(id);
        }

        if (optionalPayment.isPresent()) {
            Payment payment = optionalPayment.get();
            transactionId = payment.getTransactionId();
            bankCode = extractBankCode(payment.getBankResponse(), payment.getCode());
        }

        // If not found in DB or if it's a card registration
        if (transactionId == null) {
            String regTxKey = "registration-tx:" + id;
            transactionId = redisTemplate.opsForValue().get(regTxKey);
            if (transactionId != null) {
                redisTemplate.delete(regTxKey);
            }
        }

        // If we have a transaction ID but don't have a bank code yet (or need live check)
        if (bankCode == null && transactionId != null && !transactionId.isBlank()) {
            try {
                log.info("[Redirection] Querying Epoint getStatus for transactionId: {}", transactionId);
                EpointResponse statusResponse = epointService.getStatus(transactionId);
                if (statusResponse != null) {
                    bankCode = extractBankCode(statusResponse.bankResponse(), statusResponse.code());
                    log.info("[Redirection] Epoint status query returned bankCode: {}", bankCode);
                    
                    // Also update payment in DB if it exists
                    if (optionalPayment.isPresent()) {
                        Payment payment = optionalPayment.get();
                        updatePaymentFromEpointResponse(payment, statusResponse);
                        paymentRepository.save(payment);
                    }
                }
            } catch (Exception e) {
                log.error("[Redirection] Failed to query Epoint getStatus for transactionId: {}", transactionId, e);
            }
        }

        String azMessage = az.fitnest.payment.util.EpointBankResponseCodes.getMessage(bankCode);
        log.info("[Redirection] Resolved bankCode: {} -> {}", bankCode, azMessage);

        try {
            String encodedReason = java.net.URLEncoder.encode(azMessage, java.nio.charset.StandardCharsets.UTF_8.toString());
            if (targetUrl.contains("?")) {
                targetUrl = targetUrl + "&reason=" + encodedReason;
            } else {
                targetUrl = targetUrl + "?reason=" + encodedReason;
            }
        } catch (Exception e) {
            log.error("[Redirection] Failed to url-encode error reason: {}", azMessage, e);
        }

        log.info("[Redirection] Final error redirect URL: {}", targetUrl);
        return targetUrl;
    }

    private String extractBankCode(String bankResponseRaw, String code) {
        if (bankResponseRaw != null && !bankResponseRaw.isBlank()) {
            String[] lines = bankResponseRaw.split("[\\r\\n]+");
            for (String line : lines) {
                int idx = line.indexOf(":");
                if (idx > 0) {
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    if ("RESP_CODE".equalsIgnoreCase(key)) {
                        return value;
                    }
                }
            }
        }
        if (code != null && !code.isBlank()) {
            return code;
        }
        return null;
    }

    public String getPublicKey() {
        return epointProperties.getPublicKey();
    }

    private void assignSubscriptionIfPossible(Payment payment, EpointResponse callbackData, Long userId) {
        String fallbackAttr = callbackData != null ? callbackData.otherAttr() : null;
        paymentSubscriptionService.assignFromPaymentDescription(payment, userId, fallbackAttr);
    }

    public EpointResponse initiatePayment(Long userId, Long packageId, Long optionId, Boolean autoPaymentEnabled) {
        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
        Double amount = priceCurrency.amount;
        String currency = priceCurrency.currency;
        validatePaymentRequest(amount, currency);
        String orderId = java.util.UUID.randomUUID().toString();
        String otherAttr = (packageId != null && optionId != null)
                ? PaymentPackageRef.encode(packageId, optionId)
                : null;
        String description = Boolean.TRUE.equals(autoPaymentEnabled) ? "Fitness package monthly payment" : "Fitness package payment";
        EpointPaymentRequest request = EpointPaymentRequest.builder()
                .currency(currency != null ? currency : "AZN")
                .amount(amount)
                .language("az")
                .orderId(orderId)
                .description(description)
                .isInstallment(0)
                .refund(0)
                .otherAttr(otherAttr)
                .autoPaymentEnabled(autoPaymentEnabled)
                .build();
        return initiatePayment(request, userId);
    }

    public EpointResponse initiatePayment(Long userId, Long packageId, Long optionId) {
        return initiatePayment(userId, packageId, optionId, false);
    }

    private static String getOtherAttrValue(String otherAttr, String key) {
        if (otherAttr == null || otherAttr.isBlank()) return null;
        String[] pairs = otherAttr.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2 && kv[0].trim().equals(key)) {
                return kv[1].trim();
            }
        }
        return null;
    }

    public EpointTokenResponse createGooglePayPayment(Long userId, Long packageId, Long optionId) {
        log.info("[GooglePayCreate] (SERVICE) userId={}, packageId={}, optionId={}", userId, packageId, optionId);

        // Fetch pricing via gRPC client
        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
        Double amount = priceCurrency.amount;
        String currency = priceCurrency.currency;
        validatePaymentRequest(amount, currency);

        String orderId = java.util.UUID.randomUUID().toString();
        String description = PaymentPackageRef.encode(packageId, optionId);

        EpointTokenRequest tokenRequest = EpointTokenRequest.builder()
                .publicKey(epointProperties.getPublicKey())
                .amount(amount)
                .currency(currency != null ? currency : "AZN")
                .language("az")
                .orderId(orderId)
                .description(description)
                .build();

        // Call Epoint's /token/payment endpoint using the overloaded generic postSigned
        EpointTokenResponse tokenResponse = httpClient.postSigned("/token/payment", tokenRequest, EpointTokenResponse.class);
        log.info("[GooglePayCreate] (SERVICE) Epoint token response: status={}, transaction={}, id={}, message={}",
                tokenResponse.status(), tokenResponse.transaction(), tokenResponse.id(), tokenResponse.message());

        boolean isSuccess = "success".equalsIgnoreCase(tokenResponse.status())
                || (tokenResponse.status() == null && tokenResponse.getPaymentId() != null);
        if (!isSuccess) {
            log.error("[GooglePayCreate] Failed to create payment token. Status: {}, Message: {}", tokenResponse.status(), tokenResponse.message());
            throw new RuntimeException("Epoint token creation failed: " + tokenResponse.message());
        }

        // Store initial pending payment in local database
        Payment payment = new Payment();
        payment.setProvider("EPOINT");
        payment.setOrderId(orderId);
        payment.setTransactionId(tokenResponse.getPaymentId());
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setStatus("PENDING");
        payment.setUserId(userId);
        payment.setDescription(description);
        payment.setType("GOOGLE_PAY");
        payment.setAutoPaymentEnabled(false);
        paymentRepository.save(payment);
        log.info("[GooglePayCreate] Saved pending payment orderId={}, transactionId={}", orderId, tokenResponse.getPaymentId());

        return tokenResponse;
    }

    public GooglePaySubmitResponse submitGooglePayPayment(Long userId, GooglePaySubmitRequest request) {
        log.info("[GooglePaySubmit] (SERVICE) userId={}, paymentId={}, tokenLength={}",
                userId, request.paymentId(), request.token() != null ? request.token().length() : 0);

        Payment payment = paymentRepository.findByTransactionId(request.paymentId())
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for transaction/paymentId: " + request.paymentId()));

        // Ensure user is the owner of the payment
        if (payment.getUserId() != null && !payment.getUserId().equals(userId)) {
            log.error("[GooglePaySubmit] User mismatch. Payment userId={}, Request userId={}", payment.getUserId(), userId);
            throw new SecurityException("Unauthorized access to payment");
        }

        // Populate user ID if not set
        if (payment.getUserId() == null) {
            payment.setUserId(userId);
        }

        // 1. Build billing contact
        String customerEmail = "";
        if (userId != null) {
            var userResp = userGrpcClient.getUser(userId);
            if (userResp != null && userResp.getEmail() != null) {
                customerEmail = userResp.getEmail();
            }
        }

        GooglePaySubmitRequest.BillingAddress addr = request.billingAddress();
        StringBuilder addressBuilder = new StringBuilder();
        if (addr.address1() != null && !addr.address1().isBlank()) {
            addressBuilder.append(addr.address1().trim());
        }
        if (addr.address2() != null && !addr.address2().isBlank()) {
            if (addressBuilder.length() > 0) addressBuilder.append(", ");
            addressBuilder.append(addr.address2().trim());
        }
        if (addr.locality() != null && !addr.locality().isBlank()) {
            if (addressBuilder.length() > 0) addressBuilder.append(", ");
            addressBuilder.append(addr.locality().trim());
        }
        if (addr.postalCode() != null && !addr.postalCode().isBlank()) {
            if (addressBuilder.length() > 0) addressBuilder.append(", ");
            addressBuilder.append(addr.postalCode().trim());
        }
        if (addr.countryCode() != null && !addr.countryCode().isBlank()) {
            if (addressBuilder.length() > 0) addressBuilder.append(", ");
            addressBuilder.append(addr.countryCode().trim());
        }
        String fullAddress = addressBuilder.toString();

        EpointTokenPaymentRequest.BillingContact contact = new EpointTokenPaymentRequest.BillingContact(
            customerEmail,
            addr.phoneNumber() != null ? addr.phoneNumber() : "",
            addr.name() != null ? addr.name() : "",
            fullAddress
        );

        // 2. Base64 encode the Google Pay token string
        String base64Token = java.util.Base64.getEncoder().encodeToString(request.token().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 3. Prepare payload for Epoint /token/google/pay endpoint
        EpointTokenPaymentRequest epointReq = EpointTokenPaymentRequest.builder()
                .publicKey(epointProperties.getPublicKey())
                .id(request.paymentId())
                .token(base64Token)
                .billingContact(contact)
                .currency(payment.getCurrency() != null ? payment.getCurrency() : "AZN")
                .language("az")
                .build();

        // 4. Send signed request to Epoint /token/google/pay
        EpointResponse response = httpClient.postSigned("/token/google/pay", epointReq);
        log.info("[GooglePaySubmit] (SERVICE) Epoint response: status={}, transaction={}, redirectUrl={}",
                response.status(), response.transaction(), response.redirectUrl());

        // 5. Update local payment status
        updatePaymentFromEpointResponse(payment, response);

        String responseStatus = response.status() != null ? response.status().toLowerCase() : "error";

        if ("success".equals(responseStatus)) {
            payment.setStatus("SUCCESS");
            payment.setCallbackProcessed(true);
            paymentRepository.save(payment);

            // Assign subscription
            assignSubscriptionIfPossible(payment, response, userId);

            return new GooglePaySubmitResponse("success", null);

        } else if ("3ds".equals(responseStatus)) {
            payment.setStatus("PENDING_3DS");
            payment.setRedirectUrl(response.redirectUrl());
            paymentRepository.save(payment);

            return new GooglePaySubmitResponse("3ds", response.redirectUrl());

        } else {
            payment.setStatus("FAILED");
            paymentRepository.save(payment);

            return new GooglePaySubmitResponse("error", null);
        }
    }

    public EpointTokenResponse createApplePayPayment(Long userId, Long packageId, Long optionId) {
        log.info("[ApplePayCreate] (SERVICE) userId={}, packageId={}, optionId={}", userId, packageId, optionId);

        // Fetch pricing via gRPC client
        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
        Double amount = priceCurrency.amount;
        String currency = priceCurrency.currency;
        validatePaymentRequest(amount, currency);

        String orderId = java.util.UUID.randomUUID().toString();
        String description = PaymentPackageRef.encode(packageId, optionId);

        EpointTokenRequest tokenRequest = EpointTokenRequest.builder()
                .publicKey(epointProperties.getPublicKey())
                .amount(amount)
                .currency(currency != null ? currency : "AZN")
                .language("az")
                .orderId(orderId)
                .description(description)
                .build();

        // Call Epoint's /token/payment endpoint using the overloaded generic postSigned
        EpointTokenResponse tokenResponse = httpClient.postSigned("/token/payment", tokenRequest, EpointTokenResponse.class);
        log.info("[ApplePayCreate] (SERVICE) Epoint token response: status={}, transaction={}, id={}, message={}",
                tokenResponse.status(), tokenResponse.transaction(), tokenResponse.id(), tokenResponse.message());

        boolean isSuccess = "success".equalsIgnoreCase(tokenResponse.status())
                || (tokenResponse.status() == null && tokenResponse.getPaymentId() != null);
        if (!isSuccess) {
            log.error("[ApplePayCreate] Failed to create payment token. Status: {}, Message: {}", tokenResponse.status(), tokenResponse.message());
            throw new RuntimeException("Epoint token creation failed: " + tokenResponse.message());
        }

        // Store initial pending payment in local database
        Payment payment = new Payment();
        payment.setProvider("EPOINT");
        payment.setOrderId(orderId);
        payment.setTransactionId(tokenResponse.getPaymentId());
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setStatus("PENDING");
        payment.setUserId(userId);
        payment.setDescription(description);
        payment.setType("APPLE_PAY");
        payment.setAutoPaymentEnabled(false);
        paymentRepository.save(payment);
        log.info("[ApplePayCreate] Saved pending payment orderId={}, transactionId={}", orderId, tokenResponse.getPaymentId());

        return tokenResponse;
    }

    public ApplePaySubmitResponse submitApplePayPayment(Long userId, ApplePaySubmitRequest request) {
        log.info("[ApplePaySubmit] (SERVICE) userId={}, paymentId={}, tokenLength={}",
                userId, request.paymentId(), request.token() != null ? request.token().length() : 0);

        Payment payment = paymentRepository.findByTransactionId(request.paymentId())
                .orElseThrow(() -> new IllegalArgumentException("Payment not found for transaction/paymentId: " + request.paymentId()));

        // Ensure user is the owner of the payment
        if (payment.getUserId() != null && !payment.getUserId().equals(userId)) {
            log.error("[ApplePaySubmit] User mismatch. Payment userId={}, Request userId={}", payment.getUserId(), userId);
            throw new SecurityException("Unauthorized access to payment");
        }

        // Populate user ID if not set
        if (payment.getUserId() == null) {
            payment.setUserId(userId);
        }

        // 2. Base64 encode the Apple Pay token string
        String base64Token = java.util.Base64.getEncoder().encodeToString(request.token().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // 3. Prepare payload for Epoint /token/apple/pay endpoint using direct billingContact map
        EpointAppleTokenPaymentRequest epointReq = EpointAppleTokenPaymentRequest.builder()
                .publicKey(epointProperties.getPublicKey())
                .id(request.paymentId())
                .token(base64Token)
                .billingContact(request.billingContact())
                .currency(payment.getCurrency() != null ? payment.getCurrency() : "AZN")
                .language("az")
                .build();

        // 4. Send signed request to Epoint /token/apple/pay
        EpointResponse response = httpClient.postSigned("/token/apple/pay", epointReq);
        log.info("[ApplePaySubmit] (SERVICE) Epoint response: status={}, transaction={}, redirectUrl={}",
                response.status(), response.transaction(), response.redirectUrl());

        // 5. Update local payment status
        updatePaymentFromEpointResponse(payment, response);

        String responseStatus = response.status() != null ? response.status().toLowerCase() : "error";

        if ("success".equals(responseStatus)) {
            payment.setStatus("SUCCESS");
            payment.setCallbackProcessed(true);
            paymentRepository.save(payment);

            // Assign subscription
            assignSubscriptionIfPossible(payment, response, userId);

            return new ApplePaySubmitResponse("success", null);

        } else if ("3ds".equals(responseStatus)) {
            payment.setStatus("PENDING_3DS");
            payment.setRedirectUrl(response.redirectUrl());
            paymentRepository.save(payment);

            return new ApplePaySubmitResponse("3ds", response.redirectUrl());

        } else {
            payment.setStatus("FAILED");
            paymentRepository.save(payment);

            return new ApplePaySubmitResponse("error", null);
        }
    }

    public Object getApplePaySession(String origin) {
        log.info("[ApplePaySession] Fetching session from Epoint for origin={}", origin);
        String redisKey = "applepay:session:" + origin;
        try {
            String cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null && !cached.isBlank()) {
                log.info("[ApplePaySession] (CACHE HIT) Returning cached Apple Pay session for origin={}", origin);
                return objectMapper.readValue(cached, Object.class);
            }
        } catch (Exception e) {
            log.warn("[ApplePaySession] Error reading session from Redis cache", e);
        }

        EpointAppleSessionRequest sessionRequest = EpointAppleSessionRequest.builder()
                .publicKey(epointProperties.getPublicKey())
                .origin(origin)
                .build();
        Object session = httpClient.postSigned("/token/apple/session", sessionRequest, Object.class);

        if (session != null) {
            try {
                redisTemplate.opsForValue().set(redisKey, objectMapper.writeValueAsString(session), 35, java.util.concurrent.TimeUnit.MINUTES);
                log.info("[ApplePaySession] Cached Apple Pay session in Redis for 35m, origin={}", origin);
            } catch (Exception e) {
                log.warn("[ApplePaySession] Failed to cache Apple Pay session in Redis", e);
            }
        }
        return session;
    }
}
