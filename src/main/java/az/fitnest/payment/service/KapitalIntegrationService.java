package az.fitnest.payment.service;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.kapital.KapitalCreateOrderRequest;
import az.fitnest.payment.client.kapital.KapitalCreateOrderResponse;
import az.fitnest.payment.client.kapital.KapitalExecTranRequest;
import az.fitnest.payment.client.kapital.KapitalOrderResponse;
import az.fitnest.payment.client.kapital.KapitalProperties;
import az.fitnest.payment.client.kapital.KapitalRestClient;
import az.fitnest.payment.dto.kapital.KapitalInitiateRequest;
import az.fitnest.payment.dto.kapital.KapitalInitiateResponse;
import az.fitnest.payment.dto.kapital.KapitalRefundRequest;
import az.fitnest.payment.dto.kapital.KapitalRefundResponse;
import az.fitnest.payment.dto.kapital.KapitalStatusResponse;
import az.fitnest.payment.exception.KapitalMaintenanceException;
import az.fitnest.payment.exception.KapitalPaymentException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.enums.KapitalPaymentStatus;
import az.fitnest.payment.service.coin.CoinCheckoutHelper;
import az.fitnest.payment.service.kapital.KapitalPaymentStore;
import az.fitnest.payment.service.kapital.KapitalStatusMapper;
import az.fitnest.payment.util.PaymentPackageRef;
import az.fitnest.payment.util.PaymentSecretEncryption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Kapital Bank (HPP, Order_SMS) orchestration: create order -> redirect to HPP -> verify via
 * GET /order -> SUCCESS outbox + subscription; plus reconciliation and void/refund.
 *
 * <p>The STATUS query param on the return URL is never trusted: only GET /order decides.
 * Finalization is idempotent (callbackProcessed + status checks, row lock) so a repeated
 * callback or the reconciler cannot assign a second subscription.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KapitalIntegrationService {

    /** Default TAKSIT months when config is blank; keep in sync with kapital.active-installment-months. */
    static final Set<Integer> SUPPORTED_INSTALLMENTS = Set.of(3, 6, 12);
    static final ZoneId BAKU = ZoneId.of("Asia/Baku");

    static final String OP_VOID_FULL = "VOID_FULL";
    static final String OP_VOID_PARTIAL = "VOID_PARTIAL";
    static final String OP_REFUND = "REFUND";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KapitalProperties properties;
    private final KapitalRestClient restClient;
    private final KapitalPaymentStore paymentStore;
    private final KapitalStatusMapper statusMapper;
    private final PaymentSubscriptionService paymentSubscriptionService;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final CoinCheckoutHelper coinCheckoutHelper;
    private final PaymentSecretEncryption secretEncryption;

    /**
     * Self reference through the Spring proxy: reconcilePending() is not transactional itself, but
     * every reconcileOne() must run in its own transaction (row lock + atomic SUCCESS/outbox).
     */
    @Lazy
    @Autowired
    private KapitalIntegrationService self;

    /** Outcome of one finalization attempt. */
    public record Outcome(String localStatus, String bankStatus, String pmoResultCode) {
    }

    /** Planned bank call for a refund request. */
    public record RefundPlan(String operation, KapitalExecTranRequest body, boolean full) {
    }

    private void checkMaintenance() {
        if (properties.isMaintenanceMode()) {
            throw new KapitalMaintenanceException("Hazırda Kapital Bank ödəniş sistemində texniki işlər aparılır");
        }
    }

    // ---------------------------------------------------------------- initiate

    @Transactional
    public KapitalInitiateResponse initiatePayment(Long userId, KapitalInitiateRequest request) {
        checkMaintenance();
        // Validate before any I/O so a bad TAKSIT value never creates a payment row.
        Integer months = normalizeInstallmentMonths(request.getInstallmentMonths());

        log.info("[KAPITAL] Initiating payment userId={}, packageId={}, optionId={}, installmentMonths={}",
                userId, request.getPackageId(), request.getOptionId(), months);

        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(
                request.getPackageId(), request.getOptionId(), userId);
        validateInstallmentAgainstDuration(months, priceCurrency.durationMonths);

        var applied = coinCheckoutHelper.applyForSubscription(
                userId,
                request.getPackageId(),
                request.getOptionId(),
                request.getIsCoinUsed(),
                priceCurrency.amount);

        String transactionId = "KAPITAL_" + System.currentTimeMillis() + "_" + (1000 + RANDOM.nextInt(9000));
        String currency = priceCurrency.currency != null ? priceCurrency.currency : properties.getDefaultCurrency();
        // Package ref stays in Payment.description; the bank description only carries TAKSIT=N.
        String description = PaymentPackageRef.appendToDescription(
                request.getDescription(), request.getPackageId(), request.getOptionId());

        Payment payment = paymentStore.createPending(
                userId, transactionId, applied.finalAmountAzn(), currency, description, months);
        payment.setCoinsUsed(applied.coinsUsed());
        paymentStore.savePayment(payment);

        return registerWithBank(payment, applied.finalAmountAzn(), currency, months);
    }

    /**
     * Checkout from order-backend (campaign + FitCoin already applied): no re-pricing, so the bank
     * charges exactly {@code amount}. installmentMonths null/0 = card, 3|6|12 = TAKSIT.
     */
    @Transactional
    public KapitalInitiateResponse initiatePaymentPrePriced(
            Long userId,
            Long packageId,
            Long optionId,
            Integer installmentMonths,
            double amount,
            String currency,
            String clientOrderId,
            String campaignId,
            BigDecimal coinsUsed) {
        checkMaintenance();

        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId required for KAPITAL pre-priced payment");
        }
        if (packageId == null || optionId == null) {
            throw new IllegalArgumentException("packageId/optionId required for KAPITAL pre-priced payment");
        }
        Integer months = normalizeInstallmentMonths(installmentMonths);

        // Duration comes from the (cached) option lookup; pre-priced requests skip pricing, not validation.
        var option = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
        validateInstallmentAgainstDuration(months, option.durationMonths);

        log.info("[KAPITAL][PrePriced] userId={}, packageId={}, optionId={}, months={}, amount={}, orderId={}, campaignId={}",
                userId, packageId, optionId, months, amount, clientOrderId, campaignId);

        String resolvedCurrency = currency != null && !currency.isBlank() ? currency : properties.getDefaultCurrency();
        String description = PaymentPackageRef.encode(packageId, optionId, campaignId);

        Payment payment = paymentStore.createPending(
                userId, clientOrderId, amount, resolvedCurrency, description, months);
        payment.setCoinsUsed(coinsUsed != null ? coinsUsed : BigDecimal.ZERO);
        paymentStore.savePayment(payment);

        return registerWithBank(payment, amount, resolvedCurrency, months);
    }

    private KapitalInitiateResponse registerWithBank(Payment payment, double amount, String currency, Integer months) {
        KapitalCreateOrderRequest bankRequest = KapitalCreateOrderRequest.builder()
                .order(KapitalCreateOrderRequest.Order.builder()
                        .amount(formatAmount(amount))
                        .currency(currency)
                        .language(properties.getDefaultLanguage())
                        .title(properties.getTitle())
                        .description(bankDescription(payment.getTransactionId(), months))
                        .hppRedirectUrl(properties.getCallbackUrl())
                        .build())
                .build();

        KapitalCreateOrderResponse bankResponse;
        try {
            bankResponse = restClient.createOrder(bankRequest);
        } catch (KapitalPaymentException e) {
            paymentStore.markFailed(payment, e.getMessage(), e.getErrorCode(), null);
            throw e;
        }

        KapitalCreateOrderResponse.Order order = bankResponse == null ? null : bankResponse.getOrder();
        if (order == null || isBlank(order.getId()) || isBlank(order.getPassword()) || isBlank(order.getHppUrl())) {
            paymentStore.markFailed(payment, "Kapital Bank order yaradılmadı", "KAPITAL_ORDER_INVALID", null);
            throw new KapitalPaymentException("KAPITAL_ORDER_INVALID", "Kapital Bank ödəniş qeydiyyatı uğursuz oldu");
        }

        // The password is a one-time HPP credential: store encrypted, keep it out of redirect_url.
        paymentStore.markRegistered(
                payment,
                order.getId(),
                secretEncryption.encryptToBase64(order.getPassword()),
                buildHppUrl(order.getHppUrl(), order.getId(), null));

        log.info("[KAPITAL] Payment registered providerOrderId={}, tx={}", order.getId(), payment.getTransactionId());

        return KapitalInitiateResponse.builder()
                .providerOrderId(order.getId())
                .transactionId(payment.getTransactionId())
                .hppUrl(buildHppUrl(order.getHppUrl(), order.getId(), order.getPassword()))
                .provider(KapitalPaymentStore.PROVIDER_KAPITAL)
                .amount(amount)
                .currency(currency)
                .installmentMonths(months)
                .build();
    }

    // ------------------------------------------------------- result / finalize

    /**
     * Return-URL handler. {@code bankOrderId} is the bank's ID query param; the STATUS param is ignored.
     *
     * @return where the payer should be redirected
     */
    @Transactional
    public String processResult(String bankOrderId) {
        log.info("[KAPITAL][Result] Processing providerOrderId={}", bankOrderId);

        Optional<Payment> paymentOpt = paymentStore.findByProviderOrderIdForUpdate(bankOrderId);
        if (paymentOpt.isEmpty()) {
            log.error("[KAPITAL][Result] Payment not found providerOrderId={}", bankOrderId);
            return properties.getErrorRedirectUrl();
        }
        Outcome outcome = finalizePayment(paymentOpt.get(), false);
        return redirectFor(outcome.localStatus());
    }

    /** Status poll: finalizes via GET /order when still pending, otherwise answers from local state. */
    @Transactional
    public KapitalStatusResponse checkPaymentStatus(String providerOrderId) {
        checkMaintenance();
        Payment payment = paymentStore.findByProviderOrderIdForUpdate(providerOrderId)
                .orElseThrow(() -> new KapitalPaymentException(
                        "PAYMENT_NOT_FOUND", "Ödəniş tapılmadı: " + providerOrderId));
        Outcome outcome = finalizePayment(payment, false);
        return KapitalStatusResponse.builder()
                .providerOrderId(payment.getProviderOrderId())
                .transactionId(payment.getTransactionId())
                .status(outcome.localStatus())
                .bankStatus(outcome.bankStatus())
                .pmoResultCode(payment.getPmoResultCode())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .installmentMonths(payment.getInstallmentMonths())
                .build();
    }

    /**
     * Idempotent finalization from the bank's authoritative order state.
     * Must run in a transaction holding the payment row lock.
     *
     * @param abandonStale when true (reconciler), an order still Preparing past preparingTimeoutMs is FAILED
     */
    Outcome finalizePayment(Payment payment, boolean abandonStale) {
        // Terminal/processed rows are never touched again: this is what prevents a second subscription.
        if (Boolean.TRUE.equals(payment.getCallbackProcessed())
                || !KapitalPaymentStore.STATUS_PENDING.equals(payment.getStatus())) {
            log.info("[KAPITAL] Already finalized providerOrderId={}, status={}",
                    payment.getProviderOrderId(), payment.getStatus());
            return new Outcome(payment.getStatus(), null, payment.getPmoResultCode());
        }

        KapitalOrderResponse order;
        try {
            order = restClient.getOrder(payment.getProviderOrderId());
        } catch (KapitalPaymentException e) {
            // Bank unreachable: stay PENDING so the reconciler/poll retries; never fail on transport errors.
            log.warn("[KAPITAL] getOrder failed providerOrderId={}: {}", payment.getProviderOrderId(), e.getMessage());
            return new Outcome(payment.getStatus(), null, null);
        }

        String bankStatus = order != null ? order.getStatus() : null;
        String pmo = order != null ? order.getPmoResultCode() : null;
        KapitalPaymentStatus mapped = statusMapper.map(bankStatus);
        String summary = "status=" + bankStatus + ",pmoResultCode=" + pmo;
        log.info("[KAPITAL] providerOrderId={}, tx={}, mapped={}, {}",
                payment.getProviderOrderId(), payment.getTransactionId(), mapped, summary);

        switch (mapped) {
            case SUCCESS -> {
                paymentStore.markSuccess(payment, order);
                paymentSubscriptionService.assignFromPaymentDescription(payment);
            }
            case PARTIALLY_PAID -> paymentStore.markManualReview(
                    payment, "Kapital Bank: qismən ödəniş (PartiallyPaid) — manual yoxlama tələb olunur",
                    order, summary);
            case FAILED, REFUNDED -> paymentStore.markFailed(
                    payment, failureMessage(bankStatus, pmo), pmo, summary);
            case IN_PROGRESS -> {
                if (abandonStale && isOlderThan(payment, properties.getPreparingTimeoutMs())) {
                    paymentStore.markFailed(payment, "Kapital Bank: ödəniş vaxtında tamamlanmadı (timeout)",
                            "TIMEOUT", summary);
                }
            }
            default -> log.warn("[KAPITAL] Unrecognised bank status '{}' providerOrderId={} — leaving PENDING",
                    bankStatus, payment.getProviderOrderId());
        }
        return new Outcome(payment.getStatus(), bankStatus, pmo);
    }

    private String redirectFor(String localStatus) {
        // PENDING -> success page: the app polls status, like BOB's in-progress handling.
        return switch (localStatus == null ? "" : localStatus) {
            case KapitalPaymentStore.STATUS_SUCCESS, KapitalPaymentStore.STATUS_PENDING ->
                    properties.getSuccessRedirectUrl();
            default -> properties.getErrorRedirectUrl();
        };
    }

    // ------------------------------------------------------------ reconcile

    /** Pending KAPITAL payment ids old enough that the payer cannot still be on the HPP. */
    @Transactional(readOnly = true)
    public List<String> findReconcileCandidates() {
        LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMillis(properties.getPendingMinAgeMs()));
        return paymentStore.findStaleByStatus(List.of(KapitalPaymentStore.STATUS_PENDING), cutoff).stream()
                .map(Payment::getProviderOrderId)
                .filter(id -> !isBlank(id))
                .toList();
    }

    @Transactional
    public Outcome reconcileOne(String providerOrderId) {
        Payment payment = paymentStore.findByProviderOrderIdForUpdate(providerOrderId).orElse(null);
        if (payment == null) {
            return new Outcome(null, null, null);
        }
        return finalizePayment(payment, true);
    }

    /** Scheduler entry point. One failing payment must not block the rest of the batch. */
    public int reconcilePending() {
        int processed = 0;
        for (String providerOrderId : findReconcileCandidates()) {
            try {
                self.reconcileOne(providerOrderId);
                processed++;
            } catch (Exception e) {
                log.warn("[KAPITAL][Reconcile] providerOrderId={} failed: {}", providerOrderId, e.getMessage());
            }
        }
        return processed;
    }

    // --------------------------------------------------------------- refund

    @Transactional
    public KapitalRefundResponse refundPayment(KapitalRefundRequest request) {
        checkMaintenance();

        Payment payment = findForRefund(request);
        if (!KapitalPaymentStore.STATUS_SUCCESS.equals(payment.getStatus())) {
            throw new KapitalPaymentException("INVALID_STATUS", "Yalnız uğurlu ödənişlər geri qaytarıla bilər");
        }
        if (isBlank(payment.getProviderOrderId())) {
            throw new KapitalPaymentException("INVALID_STATUS", "Ödənişin bank order ID-si yoxdur");
        }

        RefundPlan plan = planRefund(payment, request.getAmount(), Instant.now());
        try {
            restClient.execTran(payment.getProviderOrderId(), plan.body());
        } catch (KapitalPaymentException e) {
            log.warn("[KAPITAL][Refund] {} failed providerOrderId={}: {}",
                    plan.operation(), payment.getProviderOrderId(), e.getMessage());
            return KapitalRefundResponse.builder()
                    .orderId(payment.getOrderId())
                    .providerOrderId(payment.getProviderOrderId())
                    .success(false)
                    .operation(plan.operation())
                    .errorCode(e.getErrorCode())
                    .errorMessage(e.getMessage())
                    .build();
        }

        if (plan.full()) {
            paymentStore.markRefunded(payment);
            // Coin refund + PAYMENT_REVERSED outbox (order-backend revokes the subscription).
            paymentSubscriptionService.onPaymentReversed(payment);
        } else {
            // Partial returns keep the payment SUCCESS: the subscription was paid for in part.
            payment.setMessage("Partial " + plan.operation() + " " + request.getAmount());
            paymentStore.savePayment(payment);
        }
        log.info("[KAPITAL][Refund] {} ok providerOrderId={}", plan.operation(), payment.getProviderOrderId());
        return KapitalRefundResponse.builder()
                .orderId(payment.getOrderId())
                .providerOrderId(payment.getProviderOrderId())
                .success(true)
                .operation(plan.operation())
                .build();
    }

    private Payment findForRefund(KapitalRefundRequest request) {
        Optional<Payment> found = Optional.empty();
        if (!isBlank(request.getProviderOrderId())) {
            found = paymentStore.findByProviderOrderId(request.getProviderOrderId());
        }
        if (found.isEmpty() && !isBlank(request.getOrderId())) {
            found = paymentStore.findByOrderIdOrTransactionId(request.getOrderId());
        }
        Payment payment = found.orElseThrow(() -> new KapitalPaymentException(
                "PAYMENT_NOT_FOUND", "Ödəniş tapılmadı"));
        if (!KapitalPaymentStore.PROVIDER_KAPITAL.equals(payment.getProvider())) {
            throw new KapitalPaymentException("PAYMENT_NOT_FOUND", "Ödəniş Kapital Bank ilə edilməyib");
        }
        return payment;
    }

    /**
     * Same Baku day: bank can still void (Full/Partial voidKind, no settlement fee). Later: a Refund
     * transaction. Full = amount absent or >= payment amount.
     */
    static RefundPlan planRefund(Payment payment, Double requestedAmount, Instant now) {
        BigDecimal paid = BigDecimal.valueOf(payment.getAmount() != null ? payment.getAmount() : 0d)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal requested = requestedAmount == null
                ? paid
                : BigDecimal.valueOf(requestedAmount).setScale(2, RoundingMode.HALF_UP);
        if (requested.signum() <= 0 || requested.compareTo(paid) > 0) {
            throw new KapitalPaymentException("INVALID_AMOUNT", "Geri qaytarma məbləği etibarsızdır");
        }
        boolean full = requested.compareTo(paid) >= 0;
        boolean sameDay = isSameBakuDay(payment.getCreatedDate(), now);

        if (sameDay && full) {
            return new RefundPlan(OP_VOID_FULL, KapitalExecTranRequest.voidFull(), true);
        }
        if (sameDay) {
            return new RefundPlan(OP_VOID_PARTIAL,
                    KapitalExecTranRequest.voidPartial(requested.toPlainString()), false);
        }
        return new RefundPlan(OP_REFUND, KapitalExecTranRequest.refund(requested.toPlainString()), full);
    }

    /** createdDate is stored as a JVM-local LocalDateTime; compare calendar days in Asia/Baku. */
    static boolean isSameBakuDay(LocalDateTime createdDate, Instant now) {
        if (createdDate == null) {
            return false;
        }
        var created = createdDate.atZone(ZoneId.systemDefault()).withZoneSameInstant(BAKU).toLocalDate();
        return created.equals(now.atZone(BAKU).toLocalDate());
    }

    // -------------------------------------------------------------- helpers

    /**
     * Bank-configured TAKSIT months (sorted). Used by init validation and the installment options API.
     * Blank/invalid config falls back to {@link #SUPPORTED_INSTALLMENTS}.
     */
    public List<Integer> getSupportedInstallments() {
        String active = properties.getActiveInstallmentMonths();
        if (active == null || active.isBlank()) {
            return SUPPORTED_INSTALLMENTS.stream().sorted().toList();
        }
        try {
            List<Integer> parsed = Arrays.stream(active.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .filter(m -> m > 0)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
            return parsed.isEmpty() ? SUPPORTED_INSTALLMENTS.stream().sorted().toList() : parsed;
        } catch (NumberFormatException e) {
            log.warn("[KAPITAL] Invalid active-installment-months='{}', using default {}",
                    active, SUPPORTED_INSTALLMENTS);
            return SUPPORTED_INSTALLMENTS.stream().sorted().toList();
        }
    }

    /**
     * Installment chips for a subscription option: configured Kapital months that do not exceed
     * {@code packageDurationMonths}. Empty when duration is unknown/&lt;=0 or nothing fits.
     */
    public List<Integer> getEligibleInstallments(int packageDurationMonths) {
        if (packageDurationMonths <= 0) {
            return List.of();
        }
        return getSupportedInstallments().stream()
                .filter(m -> m <= packageDurationMonths)
                .toList();
    }

    /** null/0 = card; configured months = TAKSIT; anything else is rejected. */
    Integer normalizeInstallmentMonths(Integer months) {
        if (months == null || months == 0) {
            return null;
        }
        List<Integer> supported = getSupportedInstallments();
        if (!supported.contains(months)) {
            throw new KapitalPaymentException("INVALID_INSTALLMENT",
                    "Dəstəklənməyən taksit müddəti: " + months + " (" + supported + " olmalıdır)");
        }
        return months;
    }

    /** An installment cannot outlast the package. Unknown duration (<=0) is not enforced. */
    static void validateInstallmentAgainstDuration(Integer months, int packageDurationMonths) {
        if (months != null && packageDurationMonths > 0 && months > packageDurationMonths) {
            throw new KapitalPaymentException("INVALID_INSTALLMENT",
                    "Taksit müddəti paket müddətindən çox ola bilməz");
        }
    }

    /** The bank reads TAKSIT=N from the description; card orders get a neutral reference. */
    static String bankDescription(String transactionId, Integer months) {
        return months != null ? "TAKSIT=" + months : "FitNest " + transactionId;
    }

    /** Kapital wants a decimal AZN string ("1.00"), not qepik. */
    static String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * HPP URL is {hppUrl}?id=..&password=.. when the bank already returns .../flex, otherwise
     * {hppUrl}/flex?id=... Password is omitted when null (used for the persisted, secret-free URL).
     */
    static String buildHppUrl(String hppUrl, String id, String password) {
        String base = hppUrl.trim();
        String query = "id=" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                + (password != null ? "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8) : "");
        if (base.contains("/flex")) {
            return base + (base.contains("?") ? "&" : "?") + query;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/flex?" + query;
    }

    private static String failureMessage(String bankStatus, String pmo) {
        return "Kapital Bank: " + (bankStatus != null ? bankStatus : "failed")
                + (pmo != null ? " (pmoResultCode=" + pmo + ")" : "");
    }

    private boolean isOlderThan(Payment payment, long millis) {
        LocalDateTime created = payment.getCreatedDate();
        return created != null && Duration.between(created, LocalDateTime.now()).toMillis() > millis;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
