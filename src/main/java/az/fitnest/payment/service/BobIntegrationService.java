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

    private static final SecureRandom RANDOM = new SecureRandom();

    private void checkMaintenance() {
        if (bobProperties.isMaintenanceMode()) {
            throw new BobMaintenanceException("Hazırda Bank of Baku ödəniş sistemində texniki işlər aparılır");
        }
    }

    @Transactional
    public BobInitiateResponse initiatePayment(Long userId, BobInitiateRequest request) {
        checkMaintenance();

        log.info("[BOB] Initiating payment userId={}, packageId={}, optionId={}",
                userId, request.getPackageId(), request.getOptionId());

        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(
                request.getPackageId(), request.getOptionId());

        String transactionId = "BOB_" + System.currentTimeMillis() + "_" + (1000 + RANDOM.nextInt(9000));
        String currency = priceCurrency.currency != null
                ? priceCurrency.currency
                : bobProperties.getDefaultCurrency();
        String description = paymentStore.buildPackageDescription(
                request.getPackageId(), request.getOptionId(), request.getDescription());

        Payment payment = paymentStore.createPending(
                userId,
                transactionId,
                priceCurrency.amount,
                currency,
                description,
                Boolean.TRUE.equals(request.getSaveCard()),
                null,
                null);

        String callbackUrl = bobProperties.getCallbackUrl();
        String returnUrl = callbackUrl + "?orderNumber=" + transactionId + "&status=success";
        String failUrl = callbackUrl + "?orderNumber=" + transactionId + "&status=fail";
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
            log.error("[BOB] Register order failed: errorCode={}, errorMessage={}", errorCode, errorMessage);
            paymentStore.markFailed(payment, errorMessage, errorCode, String.valueOf(bankResponse));
            throw new BobPaymentException(errorCode,
                    "Bank of Baku ödəniş qeydiyyatı uğursuz oldu: " + errorMessage);
        }

        String orderId = (String) bankResponse.get("orderId");
        String formUrl = (String) bankResponse.get("formUrl");
        paymentStore.markRegistered(payment, orderId, formUrl);

        log.info("[BOB] Payment registered orderId={}, formUrl={}", orderId, formUrl);

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
                savedCard.getCardMask());

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
        log.info("[BOB][Callback] Processing orderNumber={}, orderId={}", orderNumber, orderIdFromBank);

        Optional<Payment> paymentOpt = paymentStore.findByOrderIdOrTransactionId(orderIdFromBank, orderNumber);
        if (paymentOpt.isEmpty()) {
            log.error("[BOB][Callback] Payment not found orderNumber={}, orderId={}", orderNumber, orderIdFromBank);
            return bobProperties.getErrorRedirectUrl();
        }

        Payment payment = paymentOpt.get();

        if (Boolean.TRUE.equals(payment.getCallbackProcessed())) {
            log.info("[BOB][Callback] Already processed orderId={}", payment.getOrderId());
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

            log.info("[BOB][Callback] SmartVista orderStatus={}, actionCode={}",
                    statusResponse.getOrderStatus(), statusResponse.getActionCode());

            paymentStore.applyBankCardFields(payment, statusResponse);
            payment.setCallbackProcessed(true);

            if (bobStatus == BobPaymentStatus.APPROVED) {
                payment.setStatus(BobPaymentStore.STATUS_SUCCESS);
                paymentStore.save(payment);
                bobCardService.checkAndSaveUserCard(payment, statusResponse);
                paymentSubscriptionService.assignFromPaymentDescription(payment);
                log.info("[BOB][Callback] SUCCESS orderId={}", payment.getOrderId());
                return bobProperties.getSuccessRedirectUrl();
            }

            paymentStore.markFailed(
                    payment,
                    statusMapper.declineMessage(statusResponse),
                    statusMapper.operationCode(statusResponse),
                    "orderStatus=" + statusResponse.getOrderStatus()
                            + ",actionCode=" + statusResponse.getActionCode());
            log.warn("[BOB][Callback] FAILED orderId={}", payment.getOrderId());
            return bobProperties.getErrorRedirectUrl();
        } finally {
            redisTemplate.delete(redisLockKey);
        }
    }

    @Transactional
    public BobOrderStatusResponse checkPaymentStatus(String orderId) {
        checkMaintenance();
        BobOrderStatusResponse statusResponse = bobRestClient.getOrderStatusExtended(orderId);

        Optional<Payment> paymentOpt = paymentStore.findByOrderIdOrTransactionId(orderId, orderId);
        statusMapper.enrichStatusResponse(statusResponse, paymentOpt.orElse(null));

        if (paymentOpt.isPresent() && statusMapper.isApproved(statusResponse.getOrderStatus())) {
            Payment payment = paymentOpt.get();
            if (!BobPaymentStore.STATUS_SUCCESS.equals(payment.getStatus())) {
                paymentStore.markSuccess(payment, statusResponse);
            }
            bobCardService.checkAndSaveUserCard(payment, statusResponse);
            paymentSubscriptionService.assignFromPaymentDescription(payment);
        }

        return statusResponse;
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
