package az.fitnest.payment.service;

import az.fitnest.payment.event.PaymentOutboxService;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.service.coin.CoinPaymentProcessor;
import az.fitnest.payment.util.PaymentPackageRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Post-payment lifecycle: durable Kafka outbox for outcomes + subscription assignment.
 * Keeps bank callbacks fast (no sync order-backend gRPC in the request path).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSubscriptionService {

    private final PaymentOutboxService paymentOutboxService;
    private final CoinPaymentProcessor coinPaymentProcessor;

    public void assignFromPaymentDescription(Payment payment) {
        if (payment == null) {
            return;
        }
        onPaymentSucceeded(
                payment,
                payment.getUserId(),
                null,
                Boolean.TRUE.equals(payment.getAutoPaymentEnabled()));
    }

    /** ABB historically always passes autoPaymentEnabled=false. */
    public void assignFromPaymentDescription(Payment payment, boolean autoPaymentEnabled) {
        if (payment == null) {
            return;
        }
        onPaymentSucceeded(payment, payment.getUserId(), null, autoPaymentEnabled);
    }

    /** Epoint may carry package/option in otherAttr as well as description. */
    public void assignFromPaymentDescription(Payment payment, Long userId, String fallbackAttr) {
        if (payment == null) {
            return;
        }
        onPaymentSucceeded(
                payment,
                userId != null ? userId : payment.getUserId(),
                fallbackAttr,
                Boolean.TRUE.equals(payment.getAutoPaymentEnabled()));
    }

    public void assignFromPaymentDescription(Payment payment,
                                             Long userId,
                                             String fallbackAttr,
                                             boolean autoPaymentEnabled) {
        onPaymentSucceeded(payment, userId, fallbackAttr, autoPaymentEnabled);
    }

    /**
     * Records SUCCESS to Kafka outbox, applies coin spend/earn in-TX, and enqueues
     * durable subscription assignment (retried by {@code OutboxRelay}).
     */
    @Transactional
    public void onPaymentSucceeded(Payment payment,
                                   Long userId,
                                   String fallbackAttr,
                                   boolean autoPaymentEnabled) {
        if (payment == null) {
            return;
        }
        Long effectiveUserId = userId != null ? userId : payment.getUserId();
        log.info("[Subscription] Success lifecycle: userId={}, orderId={}, paymentId={}",
                effectiveUserId, payment.getOrderId(), payment.getId());

        paymentOutboxService.recordPaymentOutcome(payment);

        try {
            PaymentPackageRef.Ref ref = PaymentPackageRef.parseWithFallback(
                    payment.getDescription(), fallbackAttr);

            if (ref.isComplete() && effectiveUserId != null) {
                coinPaymentProcessor.onPaymentSuccess(payment);
                paymentOutboxService.requestSubscriptionAssignment(
                        effectiveUserId,
                        ref.packageId(),
                        ref.optionId(),
                        autoPaymentEnabled,
                        payment.getOrderId());
                log.info("[Subscription] Enqueued assign userId={}, packageId={}, optionId={}, autoPay={}",
                        effectiveUserId, ref.packageId(), ref.optionId(), autoPaymentEnabled);
            } else {
                log.warn("[Subscription] Skipped assign — missing packageId/optionId/userId. userId={}, desc={}",
                        effectiveUserId, payment.getDescription());
            }
        } catch (Exception ex) {
            // Outcome event is already queued; rethrow so TX rolls back and bank can retry callback.
            log.error("[Subscription] Failed preparing side-effects for orderId={}", payment.getOrderId(), ex);
            throw ex instanceof RuntimeException re ? re : new IllegalStateException(ex);
        }
    }

    /** Records FAILED/ERROR outcomes to Kafka so failures are never silent. */
    @Transactional
    public void onPaymentFailed(Payment payment) {
        if (payment == null) {
            return;
        }
        paymentOutboxService.recordPaymentOutcome(payment);
        log.info("[Payment] Enqueued FAILED outcome orderId={}, provider={}, status={}",
                payment.getOrderId(), payment.getProvider(), payment.getStatus());
    }

    /** @deprecated Prefer outbox path via {@link #onPaymentSucceeded}; kept for rare direct tests. */
    @Deprecated
    public void assign(Long userId, Long packageId, Long optionId, boolean autoPaymentEnabled) {
        paymentOutboxService.requestSubscriptionAssignment(
                userId, packageId, optionId, autoPaymentEnabled, "manual-" + userId);
    }
}
