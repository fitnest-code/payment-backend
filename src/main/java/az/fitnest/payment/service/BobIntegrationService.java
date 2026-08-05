package az.fitnest.payment.service;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.UserSubscriptionGrpcClient;
import az.fitnest.payment.client.bob.BobProperties;
import az.fitnest.payment.client.bob.BobRestClient;
import az.fitnest.payment.dto.bob.*;
import az.fitnest.payment.exception.BobMaintenanceException;
import az.fitnest.payment.exception.BobPaymentException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.model.enums.BobPaymentStatus;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.repository.UserCardRepository;
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
import java.util.UUID;

/**
 * Bank of Baku (SmartVista EPG) ödəniş inteqrasiya servisi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BobIntegrationService {

    private static final String PROVIDER_BOB = "BOB";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_REFUNDED = "REFUNDED";

    private final BobProperties bobProperties;
    private final BobRestClient bobRestClient;
    private final PaymentRepository paymentRepository;
    private final UserCardRepository userCardRepository;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final UserSubscriptionGrpcClient userSubscriptionGrpcClient;
    private final StringRedisTemplate redisTemplate;

    @org.springframework.beans.factory.annotation.Value("${payment.card-logos-base-url:https://api.fitnest.az/assets/cards}")
    private String cardLogosBaseUrl;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Maintenance rejimi yoxlanışı
     */
    private void checkMaintenance() {
        if (bobProperties.isMaintenanceMode()) {
            throw new BobMaintenanceException("Hazırda Bank of Baku ödəniş sistemində texniki işlər aparılır");
        }
    }

    /**
     * Bank of Baku ilə birbaşa (Single-Phase) ödəniş başlatma.
     */
    @Transactional
    public BobInitiateResponse initiatePayment(Long userId, BobInitiateRequest request) {
        checkMaintenance();

        log.info("[BOB][Service] Initiating payment for userId={}, packageId={}, optionId={}",
                userId, request.getPackageId(), request.getOptionId());

        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(
                request.getPackageId(), request.getOptionId());

        String transactionId = "BOB_" + System.currentTimeMillis() + "_" + (1000 + RANDOM.nextInt(9000));

        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setProvider(PROVIDER_BOB);
        payment.setTransactionId(transactionId);
        payment.setAmount(priceCurrency.amount);
        payment.setCurrency(priceCurrency.currency != null ? priceCurrency.currency : bobProperties.getDefaultCurrency());
        payment.setStatus(STATUS_PENDING);
        payment.setDescription(request.getDescription() != null ? request.getDescription() : "FitNest Subscription Payment");
        payment.setCallbackProcessed(false);
        payment.setAutoPaymentEnabled(Boolean.TRUE.equals(request.getSaveCard()));

        paymentRepository.save(payment);

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
                request.getInstallmentMonths()
        );

        String errorCode = bankResponse.get("errorCode") != null ? String.valueOf(bankResponse.get("errorCode")) : "0";
        String errorMessage = (String) bankResponse.get("errorMessage");

        if (!"0".equals(errorCode)) {
            log.error("[BOB][Service] Register order failed: errorCode={}, errorMessage={}", errorCode, errorMessage);
            payment.setStatus(STATUS_FAILED);
            payment.setMessage(errorMessage);
            paymentRepository.save(payment);
            throw new BobPaymentException(errorCode, "Bank of Baku ödəniş qeydiyyatı uğursuz oldu: " + errorMessage);
        }

        String orderId = (String) bankResponse.get("orderId");
        String formUrl = (String) bankResponse.get("formUrl");

        payment.setOrderId(orderId);
        payment.setRedirectUrl(formUrl);
        paymentRepository.save(payment);

        log.info("[BOB][Service] Payment registered successfully: orderId={}, formUrl={}", orderId, formUrl);

        return BobInitiateResponse.builder()
                .orderId(orderId)
                .transactionId(transactionId)
                .formUrl(formUrl)
                .provider(PROVIDER_BOB)
                .amount(priceCurrency.amount)
                .currency(priceCurrency.currency)
                .build();
    }

    /**
     * Yadda saxlanılmış kartla (Binding) dərhal ödəniş etmək.
     */
    @Transactional
    public BobInitiateResponse payWithSavedCard(Long userId, BobPayWithSavedCardRequest request) {
        checkMaintenance();

        UserCard savedCard = userCardRepository.findByUserIdAndCardId(userId, request.getCardId())
                .orElseThrow(() -> new BobPaymentException("CARD_NOT_FOUND", "Saxlanılmış kart tapılmadı"));

        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(
                request.getPackageId(), request.getOptionId());

        String transactionId = "BOB_BIND_" + System.currentTimeMillis() + "_" + (1000 + RANDOM.nextInt(9000));

        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setProvider(PROVIDER_BOB);
        payment.setTransactionId(transactionId);
        payment.setAmount(priceCurrency.amount);
        payment.setCurrency(priceCurrency.currency != null ? priceCurrency.currency : bobProperties.getDefaultCurrency());
        payment.setStatus(STATUS_PENDING);
        payment.setCardId(savedCard.getCardId());
        payment.setCardMask(savedCard.getCardMask());
        payment.setDescription("FitNest Saved Card Payment");
        payment.setCallbackProcessed(false);

        paymentRepository.save(payment);

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
                null
        );

        String orderId = (String) registerResponse.get("orderId");
        if (orderId == null) {
            payment.setStatus(STATUS_FAILED);
            paymentRepository.save(payment);
            throw new BobPaymentException("ORDER_REGISTRATION_FAILED", "Order qeydə alına bilmədi");
        }

        payment.setOrderId(orderId);
        paymentRepository.save(payment);

        // Saxlanılmış kartla ödənişi icra et
        Map<String, Object> bindingPayResponse = bobRestClient.payWithBinding(orderId, savedCard.getCardId());

        log.info("[BOB][Service] Binding payment executed for orderId={}: {}", orderId, bindingPayResponse);

        // Statusu yoxla
        BobOrderStatusResponse statusResponse = bobRestClient.getOrderStatusExtended(orderId);
        BobPaymentStatus bobStatus = BobPaymentStatus.fromCode(statusResponse.getOrderStatus() != null ? statusResponse.getOrderStatus() : -1);

        if (bobStatus == BobPaymentStatus.APPROVED) {
            payment.setStatus(STATUS_SUCCESS);
            payment.setRrn(statusResponse.getRrn());
            payment.setCardMask(statusResponse.getPan());
            payment.setCardName(statusResponse.getCardholderName());
            payment.setCallbackProcessed(true);
            paymentRepository.save(payment);

            userSubscriptionGrpcClient.assignSubscriptionToUser(userId, request.getPackageId(), request.getOptionId(), true);

            return BobInitiateResponse.builder()
                    .orderId(orderId)
                    .transactionId(transactionId)
                    .provider(PROVIDER_BOB)
                    .amount(priceCurrency.amount)
                    .currency(priceCurrency.currency)
                    .build();
        } else {
            payment.setStatus(STATUS_FAILED);
            payment.setMessage(statusResponse.getErrorMessage());
            paymentRepository.save(payment);
            throw new BobPaymentException("BINDING_PAYMENT_FAILED", "Saxlanılmış kartla ödəniş imtina edildi: " + statusResponse.getErrorMessage());
        }
    }

    /**
     * Bankın Callback/Webhook bildirişinin və ya Redirect cavabının emalı.
     */
    @Transactional
    public String processCallback(String orderNumber, String orderIdFromBank) {
        log.info("[BOB][Callback] Processing callback: orderNumber={}, orderId={}", orderNumber, orderIdFromBank);

        Payment payment = null;
        if (orderIdFromBank != null && !orderIdFromBank.isBlank()) {
            payment = paymentRepository.findByOrderId(orderIdFromBank).orElse(null);
        }
        if (payment == null && orderNumber != null && !orderNumber.isBlank()) {
            payment = paymentRepository.findByTransactionId(orderNumber).orElse(null);
        }

        if (payment == null) {
            log.error("[BOB][Callback] Payment not found for orderNumber={}, orderId={}", orderNumber, orderIdFromBank);
            return bobProperties.getErrorRedirectUrl();
        }

        if (Boolean.TRUE.equals(payment.getCallbackProcessed())) {
            log.info("[BOB][Callback] Callback already processed for orderId={}", payment.getOrderId());
            return STATUS_SUCCESS.equalsIgnoreCase(payment.getStatus()) ?
                    bobProperties.getSuccessRedirectUrl() : bobProperties.getErrorRedirectUrl();
        }

        String redisLockKey = "bob_callback_lock:" + payment.getTransactionId();
        Boolean acquireLock = redisTemplate.opsForValue().setIfAbsent(redisLockKey, "LOCKED", Duration.ofSeconds(30));
        if (Boolean.FALSE.equals(acquireLock)) {
            log.warn("[BOB][Callback] Callback processing already locked in Redis for orderId={}", payment.getOrderId());
            return bobProperties.getSuccessRedirectUrl();
        }

        try {
            BobOrderStatusResponse statusResponse = bobRestClient.getOrderStatusExtended(payment.getOrderId());
            BobPaymentStatus bobStatus = BobPaymentStatus.fromCode(statusResponse.getOrderStatus() != null ? statusResponse.getOrderStatus() : -1);

            log.info("[BOB][Callback] SmartVista status response: orderStatus={}, actionCode={}",
                    statusResponse.getOrderStatus(), statusResponse.getActionCode());

            String rrn = statusResponse.getRrn();
            if (rrn == null || rrn.isBlank()) {
                rrn = statusResponse.getAuthRefNum();
            }
            payment.setRrn(rrn);
            payment.setCardMask(statusResponse.getPan());
            payment.setCardName(statusResponse.getCardholderName());
            payment.setCallbackProcessed(true);

            if (bobStatus == BobPaymentStatus.APPROVED) {
                payment.setStatus(STATUS_SUCCESS);
                paymentRepository.save(payment);

                // Kart saxlanmasını yoxlamaq və saxlanılmış kartlar cədvəlinə (user_cards) yazmaq
                checkAndSaveUserCard(payment, statusResponse);

                log.info("[BOB][Callback] Payment SUCCESS for orderId={}", payment.getOrderId());
                return bobProperties.getSuccessRedirectUrl();
            } else {
                payment.setStatus(STATUS_FAILED);
                payment.setMessage(statusResponse.getErrorMessage() != null ? statusResponse.getErrorMessage() : "Payment declined");
                paymentRepository.save(payment);

                log.warn("[BOB][Callback] Payment FAILED for orderId={}", payment.getOrderId());
                return bobProperties.getErrorRedirectUrl();
            }
        } finally {
            redisTemplate.delete(redisLockKey);
        }
    }

    /**
     * Ödəniş statusunu sorğulamaq.
     */
    @Transactional
    public BobOrderStatusResponse checkPaymentStatus(String orderId) {
        checkMaintenance();
        BobOrderStatusResponse statusResponse = bobRestClient.getOrderStatusExtended(orderId);

        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId);
        if (paymentOpt.isEmpty()) {
            paymentOpt = paymentRepository.findByTransactionId(orderId);
        }

        // RRN təyin edilməsi: RRN -> AuthRefNum -> TransactionId
        String rrn = statusResponse.getRrn();
        if (rrn == null || rrn.isBlank()) {
            rrn = statusResponse.getAuthRefNum();
        }
        if ((rrn == null || rrn.isBlank()) && paymentOpt.isPresent()) {
            rrn = paymentOpt.get().getTransactionId();
        }
        statusResponse.setRrn(rrn);

        // Tarix - Saat formatlanması
        String formattedDate = null;
        if (statusResponse.getAuthDateTime() != null && !statusResponse.getAuthDateTime().isBlank()) {
            formattedDate = formatTimestampOrString(statusResponse.getAuthDateTime());
        } else if (statusResponse.getDate() != null && !statusResponse.getDate().isBlank()) {
            formattedDate = formatTimestampOrString(statusResponse.getDate());
        } else if (paymentOpt.isPresent() && paymentOpt.get().getCreatedDate() != null) {
            formattedDate = paymentOpt.get().getCreatedDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
        }
        statusResponse.setFormattedDate(formattedDate);

        // cardMask, cardBrand, bank və type təyin olunması
        String cardMask = statusResponse.getPan();
        if ((cardMask == null || cardMask.isBlank()) && paymentOpt.isPresent()) {
            cardMask = paymentOpt.get().getCardMask();
        }
        statusResponse.setCardMask(cardMask);

        String cardBrand = az.fitnest.payment.util.CardBrandDetector.detectBrand(cardMask);
        statusResponse.setCardBrand(cardBrand != null ? cardBrand : "UNKNOWN");
        statusResponse.setBank("Bank of Baku");

        // Type təyin olunması (Birdəfəlik, Taksitli ödəniş, Avtomatik uzadılma və s.)
        String paymentType = "Birdəfəlik";
        if (paymentOpt.isPresent()) {
            Payment p = paymentOpt.get();
            String t = p.getType();
            if (t != null && (t.toUpperCase().contains("INSTALLMENT") || "BOB_INSTALLMENT".equalsIgnoreCase(t))) {
                paymentType = "Taksitli ödəniş";
            } else if ("AUTO_RENEWAL".equalsIgnoreCase(t)) {
                paymentType = "Avtomatik uzadılma";
            } else if ("CARD_BIND".equalsIgnoreCase(t)) {
                paymentType = "Kartın bağlanması";
            } else if ("SAVED_CARD".equalsIgnoreCase(t)) {
                paymentType = "Yadda saxlanılmış kart";
            }
        }
        statusResponse.setType(paymentType);

        // Əgər ödəniş uğurludur (orderStatus == 2) və kart saxlanması tələb olunubsa, yadda saxlanılan kartlar cədvəlinə yazırıq
        if (paymentOpt.isPresent() && statusResponse.getOrderStatus() != null && statusResponse.getOrderStatus() == 2) {
            checkAndSaveUserCard(paymentOpt.get(), statusResponse);
        }

        return statusResponse;
    }

    private void checkAndSaveUserCard(Payment payment, BobOrderStatusResponse statusResponse) {
        if (payment == null || payment.getUserId() == null) return;

        String bindingId = statusResponse.getResolvedBindingId();
        boolean saveRequested = Boolean.TRUE.equals(payment.getAutoPaymentEnabled());

        if ((bindingId == null || bindingId.isBlank()) && saveRequested) {
            bindingId = "BOB_BIND_" + payment.getOrderId();
        }

        if (bindingId != null && !bindingId.isBlank()) {
            String cardMask = statusResponse.getPan() != null && !statusResponse.getPan().isBlank() ?
                    statusResponse.getPan() : payment.getCardMask();
            String cardName = statusResponse.getCardholderName() != null && !statusResponse.getCardholderName().isBlank() ?
                    statusResponse.getCardholderName() : "Bank of Baku Card";

            saveUserCard(payment.getUserId(), bindingId, cardMask, cardName);
        }
    }

    private String formatTimestampOrString(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) return null;
        try {
            long ts = Long.parseLong(rawDate.trim());
            return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), java.time.ZoneId.of("Asia/Baku"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
        } catch (Exception e) {
            return rawDate;
        }
    }

    /**
     * Ödənişi geri qaytarmaq (Refund).
     */
    @Transactional
    public BobRefundResponse refundPayment(BobRefundRequest request) {
        checkMaintenance();

        Payment payment = paymentRepository.findByOrderId(request.getOrderId())
                .orElseThrow(() -> new BobPaymentException("PAYMENT_NOT_FOUND", "Ödəniş tapılmadı: " + request.getOrderId()));

        if (!STATUS_SUCCESS.equals(payment.getStatus())) {
            throw new BobPaymentException("INVALID_STATUS", "Yalnız uğurlu ödənişlər geri qaytarıla bilər");
        }

        Map<String, Object> refundResponse = bobRestClient.refund(request.getOrderId(), request.getAmount());
        String errorCode = String.valueOf(refundResponse.get("errorCode"));
        String errorMessage = (String) refundResponse.get("errorMessage");

        if ("0".equals(errorCode)) {
            payment.setStatus(STATUS_REFUNDED);
            paymentRepository.save(payment);

            return BobRefundResponse.builder()
                    .orderId(request.getOrderId())
                    .success(true)
                    .errorCode("0")
                    .build();
        } else {
            return BobRefundResponse.builder()
                    .orderId(request.getOrderId())
                    .success(false)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .build();
        }
    }

    /**
     * İstifadəçinin saxlanılmış kartlarını gətirmək.
     */
    @Transactional(readOnly = true)
    public List<UserCard> getUserSavedCards(Long userId) {
        return userCardRepository.findAllByUserId(userId);
    }

    /**
     * Saxlanılmış kartı silmək (Unbind).
     */
    @Transactional
    public void deleteSavedCard(Long userId, String cardId) {
        checkMaintenance();

        UserCard userCard = userCardRepository.findByUserIdAndCardId(userId, cardId)
                .orElseThrow(() -> new BobPaymentException("CARD_NOT_FOUND", "Kart tapılmadı"));

        try {
            bobRestClient.unbindCard(cardId);
        } catch (Exception e) {
            log.warn("[BOB][Service] Bank unbindCard call failed for cardId={}, proceeding with DB deletion: {}", cardId, e.getMessage());
        }

        userCardRepository.delete(userCard);
        log.info("[BOB][Service] Saved card deleted for userId={}, cardId={}", userId, cardId);
    }

    /**
     * Bank of Baku tərəfindən dəstəklənən aktiv taksit aylarının siyahısını qaytarır.
     */
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

    private void saveUserCard(Long userId, String bindingId, String cardMask, String cardName) {
        try {
            Optional<UserCard> existing = userCardRepository.findByUserIdAndCardId(userId, bindingId);
            if (existing.isEmpty()) {
                UserCard userCard = UserCard.builder()
                        .userId(userId)
                        .cardId(bindingId)
                        .cardMask(cardMask != null ? cardMask : "**** **** **** ****")
                        .cardName(cardName != null ? cardName : "Bank Card")
                        .brand("Bank of Baku")
                        .reccPmntId(bindingId)
                        .build();
                userCardRepository.save(userCard);
                log.info("[BOB][Service] Saved new card binding for userId={}, bindingId={}", userId, bindingId);
            }
        } catch (Exception e) {
            log.error("[BOB][Service] Failed to save user card binding: userId={}, bindingId={}", userId, bindingId, e);
        }
    }
}
