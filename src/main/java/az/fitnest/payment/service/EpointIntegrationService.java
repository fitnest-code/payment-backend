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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import az.fitnest.payment.client.UserSubscriptionGrpcClient;

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
    private final UserSubscriptionGrpcClient userSubscriptionGrpcClient;

    @Transactional
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
            return idempotencyService.persistIdempotentResponse(idempotencyKey, response, payment);
        } catch (Exception e) {
            log.error("[PaymentInit] (SERVICE ERROR) Exception occurred: {}", e.getMessage(), e);
            throw e;
        }
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
        if ("success".equalsIgnoreCase(response.status()) && response.cardId() != null) {
            String redisKey = "card-reg:" + response.cardId();
            redisTemplate.opsForValue().set(redisKey, String.valueOf(userId), 30, java.util.concurrent.TimeUnit.MINUTES);
            log.info("Stored card registration mapping cardId={} -> userId={}", response.cardId(), userId);
        }
        return response;
    }

    @Transactional
    public EpointResponse executePay(EpointExecutePayRequest request, Long userId) {
        String successRedirectUrl = request.successRedirectUrl();
        String errorRedirectUrl = request.errorRedirectUrl();
        if (successRedirectUrl == null) {
            successRedirectUrl = epointProperties.getSuccessRedirectUrl();
        }
        if (errorRedirectUrl == null) {
            errorRedirectUrl = epointProperties.getErrorRedirectUrl();
        }
        request = EpointExecutePayRequest.builder()
                .publicKey(request.publicKey())
                .language(request.language())
                .orderId(request.orderId())
                .amount(request.amount())
                .currency(request.currency())
                .description(request.description())
                .resultUrl(request.resultUrl())
                .successRedirectUrl(successRedirectUrl)
                .errorRedirectUrl(errorRedirectUrl)
                .cardId(request.cardId())
                .isInstallment(request.isInstallment())
                .build();
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
        Payment payment = saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), request.autoPaymentEnabled());
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
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), false);
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
        saveDirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, null, false);
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
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), false);
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
        saveRedirectPayment(response, request.orderId(), request.amount(), request.currency(), userId, request.description(), false);
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
        log.info("[Callback] Processing callback for bankTransaction: {}, bankResponse: {}, rrn: {}, approvalCode: {}",
                callbackData.bankTransaction(), callbackData.bankResponse(), callbackData.rrn(), callbackData.approvalCode());

        if (callbackData.bankTransaction() == null || callbackData.bankTransaction().isBlank()) {
            log.error("[Callback] Missing bankTransaction");
            throw new IllegalArgumentException("error.missing_field");
        }

        if ("success".equalsIgnoreCase(callbackData.status())
                && (callbackData.rrn() == null || callbackData.rrn().isBlank())) {
            log.error("[Callback] Missing rrn for successful callback");
            throw new IllegalArgumentException("error.missing_field");
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
    public EpointResponse createWidgetUrl(Long userId, Long packageId, Long optionId, Boolean autoPaymentEnabled) {
        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
        Double amount = priceCurrency.amount;
        String currency = priceCurrency.currency;

        validatePaymentRequest(amount, currency);

        String orderId = java.util.UUID.randomUUID().toString();
        String deviceType = az.fitnest.payment.util.DeviceDetector.detectDeviceType();
        String paymentTypeDescription = Boolean.TRUE.equals(autoPaymentEnabled) ? "Monthly payment" : "One-time payment";
        String description = "packageId:" + packageId + ",optionId:" + optionId + ",device:" + deviceType + ",type:" + paymentTypeDescription;

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
            Payment payment = new Payment();
            payment.setProvider("EPOINT");
            payment.setOrderId(orderId);
            payment.setTransactionId(response.transaction());
            payment.setAmount(amount);
            payment.setCurrency(currency);
            payment.setStatus("PENDING_USER_ACTION");
            payment.setUserId(userId);
            payment.setDescription(description);
            payment.setType("WIDGET_PAYMENT");
            payment.setAutoPaymentEnabled(autoPaymentEnabled != null ? autoPaymentEnabled : false);
            paymentRepository.save(payment);
            log.info("[WidgetUrl] (SERVICE) Pending payment saved. orderId={}, transactionId={}", orderId, response.transaction());
        }

        return response;
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
            payment.setCardMask(response.cardMask());
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
            if (!card.getCardMask().equals(callbackData.cardMask())) {
                log.warn("[CardSave] Card mask mismatch for cardId {}: existing={}, new={}. Skipping update.", callbackData.cardId(), card.getCardMask(), callbackData.cardMask());
                return;
            }
            card.setCardName(getField.apply(callbackData.cardName(), "CARDNAME"));
            card.setBankTransaction(callbackData.bankTransaction());
            card.setOperationCode(callbackData.operationCode());
            card.setRrn(getField.apply(callbackData.rrn(), "RRN"));
            card.setApprovalCode(getField.apply(callbackData.approvalCode(), "APPROVAL_CODE"));
            card.setCardNumber(getField.apply(callbackData.cardNumber(), "CARD_NUMBER"));
            card.setReccPmntId(getField.apply(callbackData.reccPmntId(), "RECC_PMNT_ID"));
            card.setReccPmntExpiry(getField.apply(callbackData.reccPmntExpiry(), "RECC_PMNT_EXPIRY"));
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
                    .cardName(getField.apply(callbackData.cardName(), "CARDNAME"))
                    .brand(CardBrandDetector.detectBrand(callbackData.cardMask()))
                    .bankTransaction(callbackData.bankTransaction())
                    .operationCode(callbackData.operationCode())
                    .rrn(getField.apply(callbackData.rrn(), "RRN"))
                    .approvalCode(getField.apply(callbackData.approvalCode(), "APPROVAL_CODE"))
                    .cardNumber(getField.apply(callbackData.cardNumber(), "CARD_NUMBER"))
                    .reccPmntId(getField.apply(callbackData.reccPmntId(), "RECC_PMNT_ID"))
                    .reccPmntExpiry(getField.apply(callbackData.reccPmntExpiry(), "RECC_PMNT_EXPIRY"))
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

    public String getPublicKey() {
        return epointProperties.getPublicKey();
    }

    private void assignSubscriptionIfPossible(Payment payment, EpointResponse callbackData, Long userId) {
        log.info("[SubscriptionAssign] Entry: userId={}, paymentId={}, orderId={}", userId, payment.getId(), payment.getOrderId());
        try {
            Long packageId = null;
            Long optionId = null;
            if (payment.getDescription() != null && payment.getDescription().contains("packageId:")) {
                String[] parts = payment.getDescription().split(",");
                for (String part : parts) {
                    if (part.startsWith("packageId:")) {
                        packageId = Long.parseLong(part.replace("packageId:", "").trim());
                    } else if (part.startsWith("optionId:")) {
                        optionId = Long.parseLong(part.replace("optionId:", "").trim());
                    }
                }
            }
            if ((packageId == null || optionId == null) && callbackData.otherAttr() != null) {
                String pkg = getOtherAttrValue(callbackData.otherAttr(), "packageId");
                String opt = getOtherAttrValue(callbackData.otherAttr(), "optionId");
                if (pkg != null) packageId = Long.parseLong(pkg);
                if (opt != null) optionId = Long.parseLong(opt);
            }
            if (packageId != null && optionId != null) {
                log.info("[SubscriptionAssign] Assigning subscription via gRPC: userId={}, packageId={}, optionId={}, autoPaymentEnabled={}", userId, packageId, optionId, payment.getAutoPaymentEnabled());
                var grpcResponse = userSubscriptionGrpcClient.assignSubscriptionToUser(userId, packageId, optionId, payment.getAutoPaymentEnabled());
                log.info("[SubscriptionAssign] Subscription assignment gRPC response: {}", grpcResponse);
            } else {
                log.warn("[SubscriptionAssign] Could not extract packageId/optionId for subscription assignment. Skipping assignment. userId={}, paymentId={}, orderId={}", userId, payment.getId(), payment.getOrderId());
            }
        } catch (Exception ex) {
            log.error("[SubscriptionAssign] Error during subscription assignment via gRPC. userId={}, paymentId={}, orderId={}", userId, payment.getId(), payment.getOrderId(), ex);
        }
    }

    @Transactional
    public EpointResponse initiatePayment(Long userId, Long packageId, Long optionId, Boolean autoPaymentEnabled) {
        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
        Double amount = priceCurrency.amount;
        String currency = priceCurrency.currency;
        validatePaymentRequest(amount, currency);
        String orderId = java.util.UUID.randomUUID().toString();
        java.util.List<String> otherAttrList = new java.util.ArrayList<>();
        if (packageId != null) otherAttrList.add("packageId:" + packageId);
        if (optionId != null) otherAttrList.add("optionId:" + optionId);
        String otherAttr = otherAttrList.isEmpty() ? null : String.join(",", otherAttrList);
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

    @Transactional
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
}
