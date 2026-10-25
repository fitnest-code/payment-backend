package az.fitnest.payment.service;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.abb.bnpl.AbbBnplProperties;
import az.fitnest.payment.client.abb.bnpl.AbbBnplRestClient;
import az.fitnest.payment.dto.abb.bnpl.*;
import az.fitnest.payment.exception.BnplMaintenanceException;
import az.fitnest.payment.exception.BnplPaymentException;
import az.fitnest.payment.model.entity.AbbBnplCallbackEvent;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.enums.BnplOrderStatus;
import az.fitnest.payment.service.bnpl.BnplContractRules;
import az.fitnest.payment.service.bnpl.BnplPaymentStore;
import az.fitnest.payment.service.coin.CoinCheckoutHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * ABB BNPL orchestration: submit order, callback handling, status fallback, reverse and
 * reconciliation.
 *
 * <p>Design rules taken from the ABB contract (see {@link BnplContractRules}):</p>
 * <ul>
 *   <li>the ABB HTTP call never runs inside a database transaction;</li>
 *   <li>ambiguous submit outcomes ({@code SUBMIT_UNKNOWN}) are never {@code FAILED};</li>
 *   <li>callbacks are persisted before processing and orphan events answer 200;</li>
 *   <li>a partial reverse keeps {@code COMPLETED} until the full amount is reversed.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BnplIntegrationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REVERSE_LOCK_PREFIX = "bnpl:reverse:";
    private static final String CALLBACK_DEDUPE_PREFIX = "bnpl:callback:";

    private final AbbBnplProperties properties;
    private final AbbBnplRestClient restClient;
    private final BnplPaymentStore paymentStore;
    private final PaymentSubscriptionService paymentSubscriptionService;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CoinCheckoutHelper coinCheckoutHelper;

    public record CallbackHeaders(String callbackId,
                                  String timestamp,
                                  String signature,
                                  String authorization) {
    }

    // ===================================================================== INIT

    public BnplInitResponse initiate(Long userId, BnplInitRequest request) {
        checkMaintenance();
        validateInitRequest(request);

        String fin = request.getFin().trim().toUpperCase(Locale.ROOT);
        String phone = BnplContractRules.normalizePhone(request.getPhone());

        // Idempotency: ABB has no lookup-by-reference and rejects a second active order
        // without returning its id, so reuse our own recent unfinished order instead.
        Optional<Payment> recoverable = paymentStore.findRecoverable(
                userId, request.getPackageId(), request.getOptionId(),
                properties.getInitRecoveryWindowMinutes());
        if (recoverable.isPresent()) {
            log.info("[BNPL] Reusing unfinished order reference={} for userId={}",
                    recoverable.get().getTransactionId(), userId);
            return toRecoveredInitResponse(recoverable.get());
        }

        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(
                request.getPackageId(), request.getOptionId());
        double originalAmount = priceCurrency.amount;
        boolean useCoins = Boolean.TRUE.equals(request.getIsCoinUsed())
                || (request.getCoinsToUse() != null && request.getCoinsToUse().compareTo(BigDecimal.ZERO) > 0);
        var applied = coinCheckoutHelper.applyForSubscription(
                userId,
                request.getPackageId(),
                request.getOptionId(),
                useCoins,
                originalAmount);
        BigDecimal coins = applied.coinsUsed();
        double netAmount = applied.finalAmountAzn();
        if (coins.compareTo(BigDecimal.ZERO) < 0) {
            throw new BnplPaymentException("BNPL_INVALID_COINS", "Coin məbləği mənfi ola bilməz");
        }
        if (netAmount < properties.getMinAmount()) {
            throw new BnplPaymentException("BNPL_AMOUNT_TOO_LOW",
                    "BNPL məbləği minimum " + formatAmount(properties.getMinAmount())
                            + " AZN olmalıdır (coin endirimindən sonra)");
        }
        if (netAmount > properties.getMaxAmount()) {
            throw new BnplPaymentException("BNPL_AMOUNT_TOO_HIGH",
                    "BNPL məbləği maksimum " + formatAmount(properties.getMaxAmount()) + " AZN ola bilər");
        }

        String productName = resolveProductName(request.getPackageId(), request.getOptionId());
        String reference = generateReference();
        String description = paymentStore.buildDescription(
                request.getPackageId(), request.getOptionId(), productName);

        Payment payment = paymentStore.createPending(
                userId,
                reference,
                netAmount,
                priceCurrency.currency != null ? priceCurrency.currency : "AZN",
                description,
                fin,
                phone,
                request.getTerm(),
                productName,
                coins);

        BnplAbbSubmitRequest abbRequest = BnplAbbSubmitRequest.builder()
                .fin(fin)
                .term(BnplContractRules.formatTerm(request.getTerm()))
                .phone(phone)
                .price(netAmount)
                .productName(productName)
                .reference(reference)
                .build();

        try {
            BnplAbbSubmitResponse abbResponse = restClient.submitOrder(abbRequest);

            if (abbResponse.getOrderId() == null) {
                // No id = we cannot prove the order exists. Never FAILED.
                log.error("[BNPL] Submit returned no orderId reference={} response={}",
                        reference, writeSafe(abbResponse));
                paymentStore.markSubmitUnknown(payment.getId(),
                        "ABB sifariş ID qaytarmadı",
                        "BNPL_NO_ORDER_ID",
                        writeSafe(abbResponse));
                return submitUnknownResponse(paymentStore.findById(payment.getId()).orElse(payment));
            }

            String abbStatus = abbResponse.getStatus() != null ? abbResponse.getStatus() : "INIT";
            paymentStore.markSubmitted(payment.getId(), abbResponse.getOrderId(), abbStatus,
                    abbResponse.getMessage());

            log.info("[BNPL] Order submitted reference={} abbOrderId={} status={} term={}",
                    reference, abbResponse.getOrderId(), abbStatus, request.getTerm());

            return BnplInitResponse.success(
                    reference,
                    String.valueOf(abbResponse.getOrderId()),
                    abbStatus,
                    abbResponse.getMessage() != null
                            ? abbResponse.getMessage()
                            : BnplOrderStatus.INIT.toUserMessageAz(),
                    netAmount,
                    priceCurrency.currency,
                    request.getTerm(),
                    false);
        } catch (BnplPaymentException e) {
            return handleSubmitFailure(payment, userId, e);
        }
    }

    /**
     * Duplicate and ambiguous submits keep the order pollable instead of failing it.
     * Definitive rejections (bad FIN/phone/term, customer not found) are rethrown to the
     * controller as 400 and only those mark the payment {@code FAILED}.
     */
    private BnplInitResponse handleSubmitFailure(Payment payment, Long userId, BnplPaymentException e) {
        String detail = e.getBankDetail() != null ? e.getBankDetail() : e.getMessage();

        if (BnplContractRules.isDuplicateSubmit(detail)) {
            log.warn("[BNPL] Duplicate submit reference={} detail={}", payment.getTransactionId(), detail);
            paymentStore.markSubmitUnknown(payment.getId(),
                    "ABB-də aktiv BNPL sorğusu mövcuddur",
                    "BNPL_ORDER_ALREADY_EXISTS",
                    detail);

            Optional<Payment> existing = paymentStore.findRecentUnfinished(userId, 24).stream()
                    .filter(candidate -> !candidate.getId().equals(payment.getId()))
                    .findFirst();
            BnplInitResponse response = BnplInitResponse.builder()
                    .status("error")
                    .errorCode("BNPL_ORDER_ALREADY_EXISTS")
                    .errorMessage("Hazırda aktiv BNPL sorğunuz mövcuddur. Statusu yenidən yoxlayın.")
                    .message("Hazırda aktiv BNPL sorğunuz mövcuddur. Statusu yenidən yoxlayın.")
                    .retryable(true)
                    .existingOrder(existing.isPresent())
                    .reference(existing.map(Payment::getTransactionId).orElse(null))
                    .abbOrderId(existing.map(Payment::getOrderId).orElse(null))
                    .allowedTerms(getSupportedTerms())
                    .build();
            return response;
        }

        if (BnplContractRules.isAmbiguousSubmitFailure(e.getErrorCode(), detail)) {
            log.warn("[BNPL] Ambiguous submit reference={} code={} detail={}",
                    payment.getTransactionId(), e.getErrorCode(), detail);
            paymentStore.markSubmitUnknown(payment.getId(),
                    "Sifarişin nəticəsi dəqiqləşməyib", e.getErrorCode(), detail);
            return submitUnknownResponse(paymentStore.findById(payment.getId()).orElse(payment));
        }

        log.warn("[BNPL] Definitive submit rejection reference={} code={} detail={}",
                payment.getTransactionId(), e.getErrorCode(), detail);
        paymentStore.markRejected(payment.getId(), e.getMessage(), e.getErrorCode(), detail);
        throw e;
    }

    private BnplInitResponse submitUnknownResponse(Payment payment) {
        return BnplInitResponse.builder()
                .status("pending")
                .reference(payment.getTransactionId())
                .abbOrderId(payment.getOrderId())
                .abbStatus(payment.getCode())
                .message("Sifarişin nəticəsi hələ dəqiqləşməyib. Statusu yenidən yoxlayın.")
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .term(paymentStore.readTerm(payment))
                .retryable(true)
                .existingOrder(true)
                .allowedTerms(getSupportedTerms())
                .build();
    }

    private BnplInitResponse toRecoveredInitResponse(Payment payment) {
        BnplOrderStatus status = BnplOrderStatus.from(payment.getCode());
        return BnplInitResponse.builder()
                .status(BnplPaymentStore.STATUS_SUBMIT_UNKNOWN.equals(payment.getStatus())
                        ? "pending" : "success")
                .reference(payment.getTransactionId())
                .abbOrderId(payment.getOrderId())
                .abbStatus(status != null ? status.name() : payment.getCode())
                .message(status != null ? status.toUserMessageAz() : payment.getMessage())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .term(paymentStore.readTerm(payment))
                .retryable(status != null && !status.isTerminal())
                .existingOrder(true)
                .allowedTerms(getSupportedTerms())
                .build();
    }

    // ================================================================= CALLBACK

    /**
     * ABB callback — must answer within 5 s (ABB retries twice and then gives up).
     * The event is persisted before processing so nothing is lost when the order is
     * still unknown locally.
     */
    public void processCallback(BnplCallbackPayload payload,
                                CallbackHeaders headers,
                                byte[] rawBody) {
        boolean signatureValid = verifyCallbackAuth(headers, rawBody);

        String callbackId = headers != null ? headers.callbackId() : null;
        String raw = rawBody != null ? new String(rawBody, StandardCharsets.UTF_8) : null;

        if (isDuplicateCallback(callbackId)) {
            log.info("[BNPL][Callback] Duplicate callbackId={} ignored", callbackId);
            return;
        }

        if (payload == null || payload.getOrderId() == null) {
            // Still record it: an orderId-less callback is a support case, not a 400 storm.
            paymentStore.recordCallbackEvent(callbackId, null, payload != null ? payload.getReference() : null,
                    payload != null ? payload.getStatus() : null, raw, signatureValid, "INVALID:no-order-id");
            log.warn("[BNPL][Callback] Callback without orderId callbackId={}", callbackId);
            return;
        }

        AbbBnplCallbackEvent event = paymentStore.recordCallbackEvent(
                callbackId,
                payload.getOrderId(),
                payload.getReference(),
                payload.getStatus(),
                raw,
                signatureValid,
                "RECEIVED");

        Optional<Payment> found = paymentStore.findByAbbOrderId(String.valueOf(payload.getOrderId()));
        if (found.isEmpty() && payload.getReference() != null && !payload.getReference().isBlank()) {
            found = paymentStore.findByReference(payload.getReference());
        }
        if (found.isEmpty()) {
            // Answer 200 and let the reconciliation job apply it later.
            log.warn("[BNPL][Callback] Order not found locally orderId={} reference={} — stored for reconciliation",
                    payload.getOrderId(), payload.getReference());
            return;
        }

        try {
            String result = applyCallback(found.get(), payload, raw);
            paymentStore.markCallbackEventProcessed(event, "APPLIED:" + result);
        } catch (Exception e) {
            log.error("[BNPL][Callback] Failed to apply orderId={}", payload.getOrderId(), e);
            paymentStore.markCallbackEventProcessed(event, "ERROR:" + e.getMessage());
            throw e;
        }
    }

    private String applyCallback(Payment payment, BnplCallbackPayload payload, String raw) {
        BnplOrderStatus next = BnplOrderStatus.from(payload.getStatus());
        if (next == null) {
            log.warn("[BNPL][Callback] Unknown status={} orderId={}", payload.getStatus(), payload.getOrderId());
            return "UNKNOWN_STATUS";
        }

        var update = paymentStore.applyStatus(payment.getId(), next, raw);

        // ABB may include partial reversal info directly in the callback. Applied even when
        // the status itself did not change, so a COMPLETED→COMPLETED callback is not lost.
        if (next == BnplOrderStatus.COMPLETED && payload.getPartialReverseCount() != null
                && payload.getPartialReverseCount() > 0) {
            applyPartialReversalMeta(update.payment(), payload.getPartialReverseCount(),
                    payload.getPartialReverseAmount(), raw);
        }

        if (!update.changed()) {
            log.info("[BNPL][Callback] No-op transition orderId={} status={}", payload.getOrderId(), next);
            return "NOOP:" + next;
        }

        String previousPaymentStatus = update.previousPaymentStatus();

        if (next == BnplOrderStatus.COMPLETED && !BnplPaymentStore.STATUS_SUCCESS.equalsIgnoreCase(previousPaymentStatus)) {
            paymentSubscriptionService.assignFromPaymentDescription(update.payment(), false);
            log.info("[BNPL][Callback] COMPLETED → subscription assigned orderId={}", payload.getOrderId());
        } else if (next == BnplOrderStatus.REVERSED) {
            paymentSubscriptionService.onPaymentReversed(update.payment());
            log.info("[BNPL][Callback] REVERSED → refund processed orderId={}", payload.getOrderId());
        } else if (BnplPaymentStore.STATUS_FAILED.equalsIgnoreCase(update.payment().getStatus())
                && !BnplPaymentStore.STATUS_FAILED.equalsIgnoreCase(previousPaymentStatus)) {
            paymentSubscriptionService.onPaymentFailed(update.payment());
        }

        log.info("[BNPL][Callback] Processed orderId={} status={} previousPaymentStatus={}",
                payload.getOrderId(), next, previousPaymentStatus);
        return next.name();
    }

    /**
     * Callback auth: HMAC-SHA256 over the raw body is the contract. Basic Auth is kept as
     * a compatibility path for environments where ABB sends it instead.
     */
    private boolean verifyCallbackAuth(CallbackHeaders headers, byte[] rawBody) {
        String signature = headers != null ? headers.signature() : null;
        String authorization = headers != null ? headers.authorization() : null;
        String timestamp = headers != null ? headers.timestamp() : null;

        String secret = properties.getCallbackHmacSecret();
        String expectedUser = properties.getCallbackUsername();
        String expectedPass = properties.getCallbackPassword();

        boolean hmacConfigured = !isBlank(secret);
        boolean basicConfigured = !isBlank(expectedUser) && !isBlank(expectedPass);

        boolean signatureValid = hmacConfigured
                && BnplContractRules.isValidSignature(secret, rawBody, signature);
        boolean basicValid = basicConfigured
                && BnplContractRules.isValidBasicAuth(authorization, expectedUser, expectedPass);

        if (basicValid && !signatureValid && hmacConfigured) {
            log.warn("[BNPL][Callback] Accepted via Basic Auth — HMAC signature missing/invalid");
        }

        if (!signatureValid && !basicValid) {
            if (properties.isCallbackRequireSignature() || hmacConfigured || basicConfigured) {
                log.warn("[BNPL][Callback] Rejected — signatureValid={} basicValid={} hmacConfigured={} basicConfigured={}",
                        signatureValid, basicValid, hmacConfigured, basicConfigured);
                throw new BnplPaymentException("BNPL_CALLBACK_UNAUTHORIZED",
                        "Callback doğrulaması uğursuz", HttpStatus.UNAUTHORIZED, false, null);
            }
            log.warn("[BNPL][Callback] No callback auth configured — accepting unsigned callback (non-prod only)");
        }

        if (!isBlank(timestamp)) {
            Instant parsed = BnplContractRules.parseTimestamp(timestamp);
            if (parsed == null || !BnplContractRules.isFresh(parsed, properties.getCallbackTimestampToleranceSeconds())) {
                log.warn("[BNPL][Callback] Stale/invalid X-ABB-Timestamp={}", timestamp);
                throw new BnplPaymentException("BNPL_CALLBACK_SIGNATURE",
                        "Callback timestamp etibarsızdır", HttpStatus.UNAUTHORIZED, false, null);
            }
        } else if (properties.isCallbackRequireTimestamp()) {
            log.warn("[BNPL][Callback] Missing X-ABB-Timestamp (required)");
            throw new BnplPaymentException("BNPL_CALLBACK_SIGNATURE",
                    "X-ABB-Timestamp tələb olunur", HttpStatus.UNAUTHORIZED, false, null);
        }

        return signatureValid;
    }

    private boolean isDuplicateCallback(String callbackId) {
        if (callbackId == null || callbackId.isBlank()) {
            return false;
        }
        try {
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(CALLBACK_DEDUPE_PREFIX + callbackId, "1", Duration.ofDays(7));
            return Boolean.FALSE.equals(first);
        } catch (Exception e) {
            log.warn("[BNPL][Callback] Redis dedupe unavailable: {}", e.getMessage());
            return false;
        }
    }

    // =================================================================== STATUS

    public BnplStatusResponse refreshStatus(String referenceOrAbbOrderId) {
        checkMaintenance();
        Payment payment = findPayment(referenceOrAbbOrderId)
                .orElseThrow(() -> new BnplPaymentException("BNPL_ORDER_NOT_FOUND",
                        "BNPL sifarişi tapılmadı"));

        BnplOrderStatus local = BnplOrderStatus.from(payment.getCode());
        if (local != null && local.isTerminal()) {
            return toStatusResponse(payment, local);
        }
        if (payment.getOrderId() == null) {
            return toStatusResponse(payment, local);
        }

        try {
            long abbOrderId = abbOrderIdOf(payment);
            BnplAbbOrderDetail detail = restClient.getOrder(abbOrderId);
            BnplOrderStatus remote = BnplOrderStatus.from(detail.getStatus());
            if (remote != null) {
                var update = paymentStore.applyStatus(payment.getId(), remote, writeSafe(detail));
                if (update.changed()) {
                    if (remote == BnplOrderStatus.COMPLETED
                            && !BnplPaymentStore.STATUS_SUCCESS.equalsIgnoreCase(update.previousPaymentStatus())) {
                        paymentSubscriptionService.assignFromPaymentDescription(update.payment(), false);
                    } else if (remote == BnplOrderStatus.REVERSED) {
                        paymentSubscriptionService.onPaymentReversed(update.payment());
                    } else if (BnplPaymentStore.STATUS_FAILED.equalsIgnoreCase(update.payment().getStatus())
                            && !BnplPaymentStore.STATUS_FAILED.equalsIgnoreCase(update.previousPaymentStatus())) {
                        paymentSubscriptionService.onPaymentFailed(update.payment());
                    }
                }
                // Report the persisted status, not the raw remote one: a regressive
                // response must not make the client think COMPLETED went back to INIT.
                payment = update.payment();
                local = BnplOrderStatus.from(payment.getCode());
            }
        } catch (BnplPaymentException e) {
            log.warn("[BNPL][Status] Fallback poll failed for {}: {}", referenceOrAbbOrderId, e.getMessage());
            payment = paymentStore.findById(payment.getId()).orElse(payment);
            local = BnplOrderStatus.from(payment.getCode());
        }

        return toStatusResponse(payment, local);
    }

    public BnplStatusResponse getLocalStatus(String referenceOrAbbOrderId) {
        Payment payment = findPayment(referenceOrAbbOrderId)
                .orElseThrow(() -> new BnplPaymentException("BNPL_ORDER_NOT_FOUND",
                        "BNPL sifarişi tapılmadı"));
        return toStatusResponse(payment, BnplOrderStatus.from(payment.getCode()));
    }

    // ================================================================== REVERSE

    public void fullReverse(String referenceOrAbbOrderId) {
        checkMaintenance();
        Payment payment = findPayment(referenceOrAbbOrderId)
                .orElseThrow(() -> new BnplPaymentException("BNPL_ORDER_NOT_FOUND",
                        "BNPL sifarişi tapılmadı"));

        requireReversibleStatus(payment);
        BigDecimal alreadyReversed = paymentStore.readReversedAmount(payment);
        if (paymentStore.readPartialReverseCount(payment) > 0
                || alreadyReversed.compareTo(BigDecimal.ZERO) > 0) {
            // ABB: after the first partial reverse only further partials are allowed.
            throw new BnplPaymentException("BNPL_PARTIAL_ALREADY_DONE",
                    "Qismən geri qaytarma edildikdən sonra tam reverse mümkün deyil. "
                            + "Qalan məbləği partial reverse ilə qaytarın.",
                    HttpStatus.BAD_REQUEST, false, null);
        }
        requireReverseWindow(payment);

        BigDecimal fullAmount = totalAmountOf(payment);
        long abbOrderId = abbOrderIdOf(payment);
        withReverseLock(payment.getId(), () -> {
            restClient.fullReverse(abbOrderId);
            Payment reversed = paymentStore.recordReversal(payment.getId(), fullAmount, "full-reverse");
            paymentSubscriptionService.onPaymentReversed(reversed);
        });
        log.info("[BNPL] Full reverse done abbOrderId={}", abbOrderId);
    }

    public void partialReverse(String referenceOrAbbOrderId, double amount) {
        checkMaintenance();
        BigDecimal requested = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
        if (requested.compareTo(BigDecimal.ONE) < 0) {
            throw new BnplPaymentException("BNPL_INVALID_AMOUNT", "Partial reverse minimum 1 AZN olmalıdır");
        }

        Payment payment = findPayment(referenceOrAbbOrderId)
                .orElseThrow(() -> new BnplPaymentException("BNPL_ORDER_NOT_FOUND",
                        "BNPL sifarişi tapılmadı"));
        requireReversibleStatus(payment);
        requireReverseWindow(payment);

        long abbOrderId = abbOrderIdOf(payment);
        withReverseLock(payment.getId(), () -> {
            Payment fresh = paymentStore.findById(payment.getId()).orElse(payment);
            BigDecimal total = totalAmountOf(fresh);
            BigDecimal already = paymentStore.readReversedAmount(fresh)
                    .setScale(2, RoundingMode.HALF_UP);
            BnplContractRules.ReverseOutcome outcome =
                    BnplContractRules.reverseOutcome(total, already, requested);
            if (outcome == BnplContractRules.ReverseOutcome.EXCEEDS) {
                throw new BnplPaymentException("BNPL_INVALID_AMOUNT",
                        "Geri qaytarılan məbləğ sifariş məbləğindən çox ola bilməz. Qalan: "
                                + total.subtract(already) + " AZN");
            }

            restClient.partialReverse(abbOrderId, requested.doubleValue());

            BigDecimal cumulative = already.add(requested);
            int count = paymentStore.readPartialReverseCount(fresh) + 1;

            if (outcome == BnplContractRules.ReverseOutcome.COMPLETE) {
                Payment reversed = paymentStore.recordReversal(
                        payment.getId(), cumulative, "partial-reverse:" + requested + " (full)");
                paymentSubscriptionService.onPaymentReversed(reversed);
                log.info("[BNPL] Partial reverse completed the full amount abbOrderId={} total={}",
                        abbOrderId, cumulative);
            } else {
                paymentStore.recordPartialReversal(payment.getId(), count, cumulative,
                        "partial-reverse:" + requested);
                log.info("[BNPL] Partial reverse done abbOrderId={} amount={} cumulative={}/{}",
                        abbOrderId, requested, cumulative, total);
            }
        });
    }

    private long abbOrderIdOf(Payment payment) {
        String raw = payment.getOrderId();
        if (raw == null || raw.isBlank()) {
            throw new BnplPaymentException("BNPL_INVALID_STATUS", "ABB sifariş ID-si yoxdur");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BnplPaymentException("BNPL_INVALID_STATUS",
                    "ABB sifariş ID-si düzgün formatda deyil: " + raw);
        }
    }

    private BigDecimal totalAmountOf(Payment payment) {
        if (payment.getAmount() == null || payment.getAmount() <= 0) {
            throw new BnplPaymentException("BNPL_INVALID_STATUS", "Sifariş məbləği məlum deyil");
        }
        return BigDecimal.valueOf(payment.getAmount()).setScale(2, RoundingMode.HALF_UP);
    }

    private void requireReversibleStatus(Payment payment) {
        BnplOrderStatus status = BnplOrderStatus.from(payment.getCode());
        boolean completed = status == BnplOrderStatus.COMPLETED
                || BnplPaymentStore.STATUS_SUCCESS.equalsIgnoreCase(payment.getStatus());
        if (!completed) {
            throw new BnplPaymentException("BNPL_INVALID_STATUS",
                    "Yalnız tamamlanmış BNPL sifarişi geri qaytarıla bilər");
        }
        if (status == BnplOrderStatus.REVERSED) {
            throw new BnplPaymentException("BNPL_INVALID_STATUS", "Sifariş artıq geri qaytarılıb");
        }
        if (payment.getOrderId() == null) {
            throw new BnplPaymentException("BNPL_INVALID_STATUS", "ABB sifariş ID-si yoxdur");
        }
    }

    private void requireReverseWindow(Payment payment) {
        LocalDateTime createdAt = payment.getCreatedDate();
        if (!BnplContractRules.isWithinReverseWindow(createdAt, properties.getReverseWindowDays())) {
            throw new BnplPaymentException("BNPL_REVERSE_WINDOW_EXPIRED",
                    "Reverse yalnız sorğu tarixindən " + properties.getReverseWindowDays()
                            + " gün ərzində mümkündür");
        }
    }

    private void withReverseLock(Long paymentId, Runnable action) {
        String key = REVERSE_LOCK_PREFIX + paymentId;
        boolean locked;
        try {
            locked = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(30)));
        } catch (Exception e) {
            // Redis unavailable — fall back to the row-level lock inside the store.
            log.warn("[BNPL] Reverse lock unavailable ({}), continuing with DB checks", e.getMessage());
            action.run();
            return;
        }
        if (!locked) {
            throw new BnplPaymentException("BNPL_REVERSE_IN_PROGRESS",
                    "Bu sifariş üzrə reverse əməliyyatı davam edir");
        }
        try {
            action.run();
        } finally {
            try {
                redisTemplate.delete(key);
            } catch (Exception ignored) {
                // TTL cleans it up.
            }
        }
    }

    private void applyPartialReversalMeta(Payment payment, int count, Double amount, String raw) {
        BigDecimal already = paymentStore.readReversedAmount(payment);
        BigDecimal cumulative = already.add(amount != null
                ? BigDecimal.valueOf(amount)
                : BigDecimal.ZERO);
        paymentStore.recordPartialReversal(payment.getId(), count, cumulative,
                raw != null ? raw : "callback-partial");
    }

    // ==================================================================== TERMS

    public List<Integer> getSupportedTerms() {
        return new ArrayList<>(properties.getActiveTerms());
    }

    public List<BnplTermOption> getTermOptions() {
        List<BnplTermOption> options = new ArrayList<>();
        for (Integer term : properties.getActiveTerms()) {
            String label = properties.getTermLabels() != null
                    ? properties.getTermLabels().get(term)
                    : null;
            if (label == null || label.isBlank()) {
                label = term + " ay";
            }
            options.add(BnplTermOption.builder().value(term).label(label).build());
        }
        return options;
    }

    // ================================================================= RECONCILE

    /**
     * Local expiry + status reconciliation. ABB expires INIT orders ~15-20 min after
     * creation (5-minute job, 15-minute threshold); relying on the mobile client to poll
     * left the local row {@code PENDING_USER_ACTION} forever.
     */
    public void reconcileStaleOrders() {
        if (!properties.isReconcileEnabled()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.getInitExpiryMinutes());
        List<Payment> stale = paymentStore.findStale(
                List.of(BnplPaymentStore.STATUS_PENDING, BnplPaymentStore.STATUS_SUBMIT_UNKNOWN),
                cutoff);

        for (Payment payment : stale) {
            try {
                reconcileOne(payment);
            } catch (Exception e) {
                log.warn("[BNPL][Reconcile] Skipped paymentId={}: {}", payment.getId(), e.getMessage());
            }
        }
    }

    private void reconcileOne(Payment payment) {
        if (payment.getOrderId() == null) {
            LocalDateTime created = payment.getCreatedDate();
            boolean expired = created != null
                    && created.isBefore(LocalDateTime.now().minusHours(properties.getSubmitUnknownExpiryHours()));
            if (expired) {
                log.warn("[BNPL][Reconcile] Closing unconfirmed submit paymentId={} reference={}",
                        payment.getId(), payment.getTransactionId());
                paymentStore.markRejected(payment.getId(),
                        "Sifarişin nəticəsi təsdiqlənmədi",
                        "BNPL_SUBMIT_UNKNOWN_EXPIRED",
                        "no-order-id");
            }
            return;
        }

        long abbOrderId = abbOrderIdOf(payment);
        BnplAbbOrderDetail detail = restClient.getOrder(abbOrderId);
        BnplOrderStatus remote = BnplOrderStatus.from(detail.getStatus());
        if (remote == null) {
            return;
        }
        var update = paymentStore.applyStatus(payment.getId(), remote, writeSafe(detail));
        if (!update.changed()) {
            return;
        }
        if (remote == BnplOrderStatus.COMPLETED
                && !BnplPaymentStore.STATUS_SUCCESS.equalsIgnoreCase(update.previousPaymentStatus())) {
            paymentSubscriptionService.assignFromPaymentDescription(update.payment(), false);
        } else if (remote == BnplOrderStatus.REVERSED) {
            paymentSubscriptionService.onPaymentReversed(update.payment());
        } else if (BnplPaymentStore.STATUS_FAILED.equalsIgnoreCase(update.payment().getStatus())
                && !BnplPaymentStore.STATUS_FAILED.equalsIgnoreCase(update.previousPaymentStatus())) {
            paymentSubscriptionService.onPaymentFailed(update.payment());
        }
        log.info("[BNPL][Reconcile] {} → {} reference={}", payment.getCode(), remote, payment.getTransactionId());
    }

    /** Retries callbacks that arrived before the local order existed. */
    public void retryOrphanCallbacks() {
        if (!properties.isReconcileEnabled()) {
            return;
        }
        for (AbbBnplCallbackEvent event : paymentStore.findUnprocessedCallbacks()) {
            if (event.getPayload() == null || event.getAbbOrderId() == null) {
                continue;
            }
            Optional<Payment> payment = paymentStore.findByAbbOrderId(event.getAbbOrderId());
            if (payment.isEmpty() && event.getReference() != null) {
                payment = paymentStore.findByReference(event.getReference());
            }
            if (payment.isEmpty()) {
                continue;
            }
            try {
                BnplCallbackPayload payload = objectMapper.readValue(event.getPayload(), BnplCallbackPayload.class);
                applyCallback(payment.get(), payload, event.getPayload());
                paymentStore.markCallbackEventProcessed(event, "APPLIED:reconciled");
            } catch (Exception e) {
                log.warn("[BNPL][Reconcile] Orphan callback retry failed eventId={}: {}",
                        event.getId(), e.getMessage());
            }
        }
    }

    // ================================================================== HELPERS

    private void checkMaintenance() {
        if (properties.isMaintenanceMode()) {
            throw new BnplMaintenanceException(
                    "Hazırda ABB BNPL ödəniş sistemində texniki işlər aparılır. Xidmət tezliklə istifadəyə veriləcək");
        }
    }

    private void validateInitRequest(BnplInitRequest request) {
        if (request.getFin() == null || request.getFin().isBlank() || request.getFin().trim().length() > 7) {
            throw new BnplPaymentException("BNPL_INVALID_FIN", "FIN maksimum 7 simvol olmalıdır");
        }
        if (!BnplContractRules.isConfiguredTerm(request.getTerm(), properties.getActiveTerms())) {
            throw new BnplPaymentException("BNPL_INVALID_TERM",
                    "Dəstəklənməyən kredit müddəti. İcazə verilənlər: " + getSupportedTerms());
        }
        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new BnplPaymentException("BNPL_INVALID_PHONE", "Telefon nömrəsi məcburidir");
        }
        BnplContractRules.normalizePhone(request.getPhone());
    }

    private String resolveProductName(Long packageId, Long optionId) {
        try {
            var names = subscriptionPackageGrpcClient.getPackageNamesByIds(List.of(packageId));
            if (names != null && !names.isEmpty()) {
                String name = names.getFirst().getName();
                if (name != null && !name.isBlank()) {
                    return name + " - option " + optionId;
                }
            }
        } catch (Exception e) {
            log.debug("[BNPL] Could not resolve package name: {}", e.getMessage());
        }
        return properties.getDefaultProductName() + " #" + packageId;
    }

    private String generateReference() {
        return "FITNEST-BNPL-" + System.currentTimeMillis() + "-" + (1000 + RANDOM.nextInt(9000));
    }

    private Optional<Payment> findPayment(String referenceOrAbbOrderId) {
        if (referenceOrAbbOrderId == null || referenceOrAbbOrderId.isBlank()) {
            return Optional.empty();
        }
        Optional<Payment> byRef = paymentStore.findByReference(referenceOrAbbOrderId);
        if (byRef.isPresent()) {
            return byRef;
        }
        return paymentStore.findByAbbOrderId(referenceOrAbbOrderId);
    }

    private BnplStatusResponse toStatusResponse(Payment payment, BnplOrderStatus abbStatus) {
        BnplOrderStatus status = abbStatus != null
                ? abbStatus
                : BnplOrderStatus.from(payment.getCode());
        String message = status != null ? status.toUserMessageAz() : payment.getMessage();

        BigDecimal total = BigDecimal.valueOf(payment.getAmount() != null ? payment.getAmount() : 0d)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal reversed = paymentStore.readReversedAmount(payment).setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = total.subtract(reversed).max(BigDecimal.ZERO);
        int partialCount = paymentStore.readPartialReverseCount(payment);

        boolean completed = status == BnplOrderStatus.COMPLETED
                || BnplPaymentStore.STATUS_SUCCESS.equalsIgnoreCase(payment.getStatus());
        boolean submitUnknown = BnplPaymentStore.STATUS_SUBMIT_UNKNOWN.equals(payment.getStatus());

        return BnplStatusResponse.builder()
                .reference(payment.getTransactionId())
                .abbOrderId(payment.getOrderId())
                .abbStatus(status != null ? status.name() : payment.getCode())
                .paymentStatus(payment.getStatus())
                .message(message)
                .terminal(status != null && status.isTerminal())
                .success(status != null && status.isSuccess())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .term(paymentStore.readTerm(payment))
                .partialReverseCount(partialCount)
                .reversedAmount(reversed.doubleValue())
                .reversableAmount(remaining.doubleValue())
                .fullReverseAllowed(completed && partialCount == 0 && reversed.signum() == 0)
                .partialReverseAllowed(completed && remaining.signum() > 0)
                .retryable(submitUnknown || (status != null && !status.isTerminal()))
                .submitUnknown(submitUnknown)
                .build();
    }

    private String writeSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String formatAmount(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
