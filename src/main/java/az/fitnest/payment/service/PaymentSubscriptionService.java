package az.fitnest.payment.service;

import az.fitnest.payment.client.UserSubscriptionGrpcClient;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.util.PaymentPackageRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Triggers subscription assignment in order-backend after successful payment.
 * Parsing of package/option refs stays here; assign business rules live in order-backend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentSubscriptionService {

    private final UserSubscriptionGrpcClient userSubscriptionGrpcClient;

    public void assignFromPaymentDescription(Payment payment) {
        if (payment == null) {
            return;
        }
        assignFromPaymentDescription(
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
        assignFromPaymentDescription(payment, payment.getUserId(), null, autoPaymentEnabled);
    }

    /** Epoint may carry package/option in otherAttr as well as description. */
    public void assignFromPaymentDescription(Payment payment, Long userId, String fallbackAttr) {
        if (payment == null) {
            return;
        }
        assignFromPaymentDescription(
                payment,
                userId != null ? userId : payment.getUserId(),
                fallbackAttr,
                Boolean.TRUE.equals(payment.getAutoPaymentEnabled()));
    }

    public void assignFromPaymentDescription(Payment payment,
                                             Long userId,
                                             String fallbackAttr,
                                             boolean autoPaymentEnabled) {
        if (payment == null) {
            return;
        }
        log.info("[Subscription] Attempting assign: userId={}, orderId={}, paymentId={}",
                userId, payment.getOrderId(), payment.getId());
        try {
            PaymentPackageRef.Ref ref = PaymentPackageRef.parseWithFallback(
                    payment.getDescription(), fallbackAttr);

            if (ref.isComplete() && userId != null) {
                assign(userId, ref.packageId(), ref.optionId(), autoPaymentEnabled);
                log.info("[Subscription] Assigned userId={}, packageId={}, optionId={}, autoPay={}",
                        userId, ref.packageId(), ref.optionId(), autoPaymentEnabled);
            } else {
                log.warn("[Subscription] Skipped — missing packageId/optionId/userId. userId={}, desc={}",
                        userId, payment.getDescription());
            }
        } catch (Exception ex) {
            log.error("[Subscription] Failed to assign for orderId={}. Payment succeeded; subscription NOT assigned.",
                    payment.getOrderId(), ex);
        }
    }

    public void assign(Long userId, Long packageId, Long optionId, boolean autoPaymentEnabled) {
        userSubscriptionGrpcClient.assignSubscriptionToUser(userId, packageId, optionId, autoPaymentEnabled);
    }
}
