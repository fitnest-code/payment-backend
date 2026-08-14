package az.fitnest.payment.service;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.bob.BobProperties;
import az.fitnest.payment.client.bob.BobRestClient;
import az.fitnest.payment.dto.bob.*;
import az.fitnest.payment.exception.BobMaintenanceException;
import az.fitnest.payment.exception.BobPaymentException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.model.enums.BobPaymentStatus;
import az.fitnest.payment.service.bob.BobCardService;
import az.fitnest.payment.service.bob.BobPaymentStore;
import az.fitnest.payment.service.bob.BobStatusMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bank of Baku (SmartVista EPG) payment orchestration facade.
 * Persistence, cards, subscriptions, and status mapping live in dedicated collaborators.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BobIntegrationService {

    private final BobProperties bobProperties;
    private final BobRestClient bobRestClient;
    private final BobPaymentStore paymentStore;
    private final BobCardService bobCardService;
    private final BobStatusMapper statusMapper;
    private final PaymentSubscriptionService paymentSubscriptionService;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final StringRedisTemplate redisTemplate;
    private final az.fitnest.payment.client.UserGrpcClient userGrpcClient;

    private static final SecureRandom RANDOM = new SecureRandom();

    private void checkMaintenance() {
        if (bobProperties.isMaintenanceMode()) {
            throw new BobMaintenanceException("Hazırda Bank of Baku ödəniş sistemində texniki işlər aparılır");
        }
    }

    @Transactional
    public BobInitiateResponse initiatePayment(Long userId, BobInitiateRequest request) {
        checkMaintenance();

        log.warn("[BOB] Initiating payment userId={}, packageId={}, optionId={}, saveCard={}, installmentMonths={}",
                userId, request.getPackageId(), request.getOptionId(),
                request.getSaveCard(), request.getInstallmentMonths());

        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(
                request.getPackageId(), request.getOptionId());

        String transactionId = "BOB_" + System.currentTimeMillis() + "_" + (1000 + RANDOM.nextInt(9000));
        String currency = priceCurrency.currency != null
                ? priceCurrency.currency
                : bobProperties.getDefaultCurrency();
        String description = paymentStore.buildPackageDescription(
                request.getPackageId(), request.getOptionId(), request.getDescription());

        boolean installment = request.getInstallmentMonths() != null && request.getInstallmentMonths() >= 1;
        String paymentType = installment ? "BOB_INSTALLMENT" : "BOB_PAYMENT";

        Payment payment = paymentStore.createPending(
                userId,
                transactionId,
                priceCurrency.amount,
                currency,
                description,
                Boolean.TRUE.equals(request.getSaveCard()),
                null,
                null,
                paymentType);

        String callbackUrl = bobProperties.getCallbackUrl();
        String returnUrl = callbackUrl + "?orderNumber=" + transactionId + "&status=success";
        String failUrl = callbackUrl + "?orderNumber=" + transactionId + "&status=fail";
        // Bank of Baku (fitnest_api) üçün Binding/Card Storage funksiyası aktiv olduğu üçün
        // saveCard=true olduqda clientId göndəririk ki, bank kartı yadda saxlasın.
        String clientId = Boolean.TRUE.equals(request.getSaveCard()) ? String.valueOf(userId) : null;

        Map<String, Object> bankResponse = bobRestClient.registerOrder(
                transactionId,
                priceCurrency.amount,
                payment.getDescription(),
                returnUrl,
                failUrl,
                clientId,
                request.getInstallmentMonths());

        String errorCode = bankResponse.get("errorCode") != null
                ? String.valueOf(bankResponse.get("errorCode"))
                : "0";
        String errorMessage = (String) bankResponse.get("errorMessage");

        if (!"0".equals(errorCode)) {
            log.error("[BOB] Register order failed: errorCode={}, errorMessage={}, saveCard={}, clientIdPresent={}, bankResponse={}",
                    errorCode, errorMessage, Boolean.TRUE.equals(request.getSaveCard()), clientId != null, bankResponse);
            paymentStore.markFailed(payment, errorMessage, errorCode, String.valueOf(bankResponse));
            throw new BobPaymentException(errorCode,
                    "Bank of Baku ödəniş qeydiyyatı uğursuz oldu: " + errorMessage);
        }

        String orderId = (String) bankResponse.get("orderId");
        String formUrl = (String) bankResponse.get("formUrl");
        paymentStore.markRegistered(payment, orderId, formUrl);

        log.warn("[BOB] Payment registered orderId={}, formUrl={}, saveCard={}",
                orderId, formUrl, Boolean.TRUE.equals(request.getSaveCard()));

        return BobInitiateResponse.builder()
                .orderId(orderId)
                .transactionId(transactionId)
                .formUrl(formUrl)
                .provider(BobPaymentStore.PROVIDER_BOB)
                .amount(priceCurrency.amount)
                .currency(priceCurrency.currency)
                .build();
    }

    @Transactional
    public BobInitiateResponse payWithSavedCard(Long userId, BobPayWithSavedCardRequest request) {
        checkMaintenance();

        UserCard savedCard = bobCardService.requireSavedCard(userId, request.getCardId());

        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(
                request.getPackageId(), request.getOptionId());

        String transactionId = "BOB_BIND_" + System.currentTimeMillis() + "_" + (1000 + RANDOM.nextInt(9000));
        String currency = priceCurrency.currency != null
                ? priceCurrency.currency
                : bobProperties.getDefaultCurrency();

        Payment payment = paymentStore.createPending(
                userId,
                transactionId,
                priceCurrency.amount,
                currency,
                "FitNest Saved Card Payment",
                false,
                savedCard.getCardId(),
                savedCard.getCardMask(),
                "SAVED_CARD");

        String callbackUrl = bobProperties.getCallbackUrl();
        String returnUrl = callbackUrl + "?orderNumber=" + transactionId + "&status=success";
        String failUrl = callbackUrl + "?orderNumber=" + transactionId + "&status=fail";

        Map<String, Object> registerResponse = bobRestClient.registerOrder(
                transactionId,
                priceCurrency.amount,
                payment.getDescription(),
                returnUrl,
                failUrl,
                String.valueOf(userId),
                null);

        String orderId = (String) registerResponse.get("orderId");
        if (orderId == null) {
            paymentStore.markFailed(payment, "Order registration failed", null, String.valueOf(registerResponse));
            throw new BobPaymentException("ORDER_REGISTRATION_FAILED", "Order qeydə alına bilmədi");
        }

        paymentStore.markRegistered(payment, orderId, null);

        Map<String, Object> bindingPayResponse = bobRestClient.payWithBinding(orderId, savedCard.getCardId());
        log.info("[BOB] Binding payment executed orderId={}: {}", orderId, bindingPayResponse);

        BobOrderStatusResponse statusResponse = bobRestClient.getOrderStatusExtended(orderId);
        BobPaymentStatus bobStatus = statusMapper.toBobStatus(statusResponse.getOrderStatus());

        if (bobStatus == BobPaymentStatus.APPROVED) {
            paymentStore.markSuccess(payment, statusResponse);
            paymentSubscriptionService.assign(
                    userId, request.getPackageId(), request.getOptionId(), true);

            return BobInitiateResponse.builder()
                    .orderId(orderId)
                    .transactionId(transactionId)
                    .provider(BobPaymentStore.PROVIDER_BOB)
                    .amount(priceCurrency.amount)
                    .currency(priceCurrency.currency)
                    .build();
        }

        paymentStore.markFailed(
                payment,
                statusMapper.declineMessage(statusResponse),
                statusMapper.operationCode(statusResponse),
                String.valueOf(statusResponse));
        throw new BobPaymentException("BINDING_PAYMENT_FAILED",
                "Saxlanılmış kartla ödəniş imtina edildi: " + statusMapper.declineMessage(statusResponse));
    }

    @Transactional
    public String processCallback(String orderNumber, String orderIdFromBank) {
        log.warn("[BOB][Callback] Processing orderNumber={}, orderId={}", orderNumber, orderIdFromBank);

        Optional<Payment> paymentOpt = paymentStore.findByOrderIdOrTransactionId(orderIdFromBank, orderNumber);
        if (paymentOpt.isEmpty()) {
            log.error("[BOB][Callback] Payment not found orderNumber={}, orderId={}", orderNumber, orderIdFromBank);
            return bobProperties.getErrorRedirectUrl();
        }

        Payment payment = paymentOpt.get();

        if (Boolean.TRUE.equals(payment.getCallbackProcessed())) {
            log.warn("[BOB][Callback] Already processed orderId={} dbStatus={}",
                    payment.getOrderId(), payment.getStatus());
            return BobPaymentStore.STATUS_SUCCESS.equalsIgnoreCase(payment.getStatus())
                    ? bobProperties.getSuccessRedirectUrl()
                    : bobProperties.getErrorRedirectUrl();
        }

        String redisLockKey = "bob_callback_lock:" + payment.getTransactionId();
        Boolean acquireLock = redisTemplate.opsForValue()
                .setIfAbsent(redisLockKey, "LOCKED", Duration.ofSeconds(30));
        if (Boolean.FALSE.equals(acquireLock)) {
            log.warn("[BOB][Callback] Redis lock held orderId={}", payment.getOrderId());
            return bobProperties.getSuccessRedirectUrl();
        }

        try {
            BobOrderStatusResponse statusResponse = bobRestClient.getOrderStatusExtended(payment.getOrderId());
            BobPaymentStatus bobStatus = statusMapper.toBobStatus(statusResponse.getOrderStatus());

            log.warn("[BOB][Callback] SmartVista mappedStatus={} saveCard={} userId={} tx={} {}",
                    bobStatus,
                    Boolean.TRUE.equals(payment.getAutoPaymentEnabled()),
                    payment.getUserId(),
                    payment.getTransactionId(),
                    bankStatusSummary(statusResponse));

            paymentStore.applyBankCardFields(payment, statusResponse);
            payment.setCallbackProcessed(true);

            if (bobStatus == BobPaymentStatus.APPROVED) {
                payment.setStatus(BobPaymentStore.STATUS_SUCCESS);
                String bindingId = statusResponse.getResolvedBindingId();
                if (bindingId != null && !bindingId.isBlank()) {
                    payment.setCardId(bindingId);
                }
                paymentStore.save(payment);
                bobCardService.checkAndSaveUserCard(payment, statusResponse);
                paymentSubscriptionService.assignFromPaymentDescription(payment);
                log.warn("[BOB][Callback] SUCCESS orderId={} bindingId={} rrn={}",
                        payment.getOrderId(),
                        statusResponse.getResolvedBindingId(),
                        statusResponse.getRrn());
                return bobProperties.getSuccessRedirectUrl();
            }

            String declineMessage = statusMapper.declineMessage(statusResponse);
            paymentStore.markFailed(
                    payment,
                    declineMessage,
                    statusMapper.operationCode(statusResponse),
                    bankStatusSummary(statusResponse));
            log.warn("[BOB][Callback] FAILED orderId={} mappedStatus={} declineMessage={} {}",
                    payment.getOrderId(), bobStatus, declineMessage, bankStatusSummary(statusResponse));
            return bobProperties.getErrorRedirectUrl();
        } finally {
            redisTemplate.delete(redisLockKey);
        }
    }

    @Transactional
    public BobOrderStatusResponse checkPaymentStatus(String orderId) {
        checkMaintenance();
        BobOrderStatusResponse statusResponse = bobRestClient.getOrderStatusExtended(orderId);
        statusResponse.flattenBankPayload();

        Optional<Payment> paymentOpt = paymentStore.findByOrderIdOrTransactionId(orderId, orderId);
        String lang = resolveLanguage(paymentOpt.map(Payment::getUserId).orElse(null));
        statusMapper.enrichStatusResponse(statusResponse, paymentOpt.orElse(null), lang);

        log.warn("[BOB][Status] orderId={} dbStatus={} type={} lang={} saveCard={} {}",
                orderId,
                paymentOpt.map(Payment::getStatus).orElse(null),
                statusResponse.getType(),
                lang,
                paymentOpt.map(Payment::getAutoPaymentEnabled).orElse(null),
                bankStatusSummary(statusResponse));

        if (paymentOpt.isPresent()) {
            Payment payment = paymentOpt.get();
            if (statusMapper.isApproved(statusResponse.getOrderStatus())) {
                if (!BobPaymentStore.STATUS_SUCCESS.equals(payment.getStatus())) {
                    paymentStore.markSuccess(payment, statusResponse);
                } else {
                    paymentStore.applyBankCardFields(payment, statusResponse);
                    paymentStore.save(payment);
                }
                String bindingId = statusResponse.getResolvedBindingId();
                if (bindingId != null && !bindingId.isBlank()) {
                    payment.setCardId(bindingId);
                    paymentStore.save(payment);
                }
                bobCardService.checkAndSaveUserCard(payment, statusResponse);
                paymentSubscriptionService.assignFromPaymentDescription(payment);
            } else if (statusMapper.isTerminalFailure(statusResponse.getOrderStatus())
                    && !BobPaymentStore.STATUS_FAILED.equals(payment.getStatus())
                    && !BobPaymentStore.STATUS_SUCCESS.equals(payment.getStatus())
                    && !BobPaymentStore.STATUS_REFUNDED.equals(payment.getStatus())) {
                paymentStore.applyBankCardFields(payment, statusResponse);
                paymentStore.markFailed(
                        payment,
                        statusMapper.declineMessage(statusResponse),
                        statusMapper.operationCode(statusResponse),
                        bankStatusSummary(statusResponse));
            }
        }

        return statusResponse;
    }

    private String resolveLanguage(Long userId) {
        try {
            if (userId != null) {
                String lang = userGrpcClient.getUserLanguage(userId);
                if (lang != null && !lang.isBlank()) {
                    return az.fitnest.payment.util.PaymentTypeLabels.normalizeLang(lang);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
                String accept = servletAttrs.getRequest().getHeader("Accept-Language");
                if (accept != null && !accept.isBlank()) {
                    return az.fitnest.payment.util.PaymentTypeLabels.normalizeLang(accept.split("[,;-]")[0]);
                }
            }
        } catch (Exception ignored) {
        }
        return "AZ";
    }

    private String bankStatusSummary(BobOrderStatusResponse status) {
        if (status == null) {
            return "status=null";
        }
        return "orderStatus=" + status.getOrderStatus()
                + ",actionCode=" + status.getActionCode()
                + ",actionCodeDescription=" + status.getActionCodeDescription()
                + ",errorCode=" + status.getErrorCode()
                + ",errorMessage=" + status.getErrorMessage()
                + ",rrn=" + status.getRrn()
                + ",authRefNum=" + status.getAuthRefNum()
                + ",pan=" + status.getPan()
                + ",bindingId=" + status.getResolvedBindingId()
                + ",approvalCode=" + status.getApprovalCode();
    }

    @Transactional
    public BobRefundResponse refundPayment(BobRefundRequest request) {
        checkMaintenance();

        Payment payment = paymentStore.findByOrderId(request.getOrderId())
                .orElseThrow(() -> new BobPaymentException(
                        "PAYMENT_NOT_FOUND", "Ödəniş tapılmadı: " + request.getOrderId()));

        if (!BobPaymentStore.STATUS_SUCCESS.equals(payment.getStatus())) {
            throw new BobPaymentException("INVALID_STATUS", "Yalnız uğurlu ödənişlər geri qaytarıla bilər");
        }

        Map<String, Object> refundResponse = bobRestClient.refund(request.getOrderId(), request.getAmount());
        String errorCode = String.valueOf(refundResponse.get("errorCode"));
        String errorMessage = (String) refundResponse.get("errorMessage");

        if ("0".equals(errorCode)) {
            paymentStore.markRefunded(payment);
            return BobRefundResponse.builder()
                    .orderId(request.getOrderId())
                    .success(true)
                    .errorCode("0")
                    .build();
        }

        return BobRefundResponse.builder()
                .orderId(request.getOrderId())
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    @Transactional(readOnly = true)
    public List<UserCard> getUserSavedCards(Long userId) {
        return bobCardService.getUserSavedCards(userId);
    }

    @Transactional
    public void deleteSavedCard(Long userId, String cardId) {
        checkMaintenance();
        bobCardService.deleteSavedCard(userId, cardId);
    }

    @Transactional(readOnly = true)
    public List<Integer> getSupportedInstallments() {
        String active = bobProperties.getActiveInstallmentMonths();
        if (active == null || active.isBlank()) {
            return List.of(2, 3, 6, 9, 12);
        }
        return java.util.Arrays.stream(active.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .toList();
    }
}
