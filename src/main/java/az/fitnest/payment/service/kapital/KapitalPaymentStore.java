package az.fitnest.payment.service.kapital;

import az.fitnest.payment.client.kapital.KapitalOrderResponse;
import az.fitnest.payment.event.PaymentOutboxService;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persists and updates KAPITAL payment rows only — no bank I/O.
 *
 * <p>payments.order_id/transaction_id hold the FitNest order id; the bank order id lives in
 * provider_order_id. SUCCESS outbox + subscription are written by PaymentSubscriptionService,
 * failures by {@link #markFailed}.</p>
 */
@Component
@RequiredArgsConstructor
public class KapitalPaymentStore {

    public static final String PROVIDER_KAPITAL = "KAPITAL";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REFUNDED = "REFUNDED";
    /** PartiallyPaid etc.: money state is unclear, support must decide. No subscription, no outbox. */
    public static final String STATUS_MANUAL_REVIEW = "MANUAL_REVIEW";

    public static final String TYPE_CARD = "KAPITAL_PAYMENT";
    public static final String TYPE_INSTALLMENT = "KAPITAL_INSTALLMENT";

    private static final int BANK_RESPONSE_MAX_LEN = 4000;

    private final PaymentRepository paymentRepository;
    private final PaymentOutboxService paymentOutboxService;

    @Transactional
    public Payment createPending(Long userId,
                                 String transactionId,
                                 Double amount,
                                 String currency,
                                 String description,
                                 Integer installmentMonths) {
        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setProvider(PROVIDER_KAPITAL);
        payment.setTransactionId(transactionId);
        // Outbox events key on orderId; for KAPITAL it is the FitNest id (bank id is providerOrderId).
        payment.setOrderId(transactionId);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setStatus(STATUS_PENDING);
        payment.setDescription(description);
        payment.setCallbackProcessed(false);
        payment.setAutoPaymentEnabled(false);
        payment.setInstallmentMonths(installmentMonths);
        payment.setType(installmentMonths != null ? TYPE_INSTALLMENT : TYPE_CARD);
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment savePayment(Payment payment) {
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByProviderOrderId(String providerOrderId) {
        if (providerOrderId == null || providerOrderId.isBlank()) {
            return Optional.empty();
        }
        return paymentRepository.findByProviderOrderId(providerOrderId);
    }

    /** Locks the row; must run inside a transaction (finalization path). */
    public Optional<Payment> findByProviderOrderIdForUpdate(String providerOrderId) {
        if (providerOrderId == null || providerOrderId.isBlank()) {
            return Optional.empty();
        }
        return paymentRepository.findByProviderOrderIdForUpdate(providerOrderId);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderIdOrTransactionId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Optional<Payment> byOrder = paymentRepository.findByOrderId(id);
        return byOrder.isPresent() ? byOrder : paymentRepository.findByTransactionId(id);
    }

    @Transactional(readOnly = true)
    public List<Payment> findStaleByStatus(Collection<String> statuses, LocalDateTime createdBefore) {
        return paymentRepository.findByProviderAndStatusInAndCreatedDateBefore(
                PROVIDER_KAPITAL, statuses, createdBefore);
    }

    @Transactional
    public Payment markRegistered(Payment payment, String providerOrderId, String encryptedPassword, String redirectUrl) {
        payment.setProviderOrderId(providerOrderId);
        payment.setEncryptedOrderPassword(encryptedPassword);
        payment.setRedirectUrl(redirectUrl);
        return paymentRepository.save(payment);
    }

    /** Caller must follow with PaymentSubscriptionService.assignFromPaymentDescription (outbox + subscription). */
    @Transactional
    public Payment markSuccess(Payment payment, KapitalOrderResponse order) {
        payment.setStatus(STATUS_SUCCESS);
        payment.setCallbackProcessed(true);
        applyBankFields(payment, order);
        return paymentRepository.save(payment);
    }

    /** Records the FAILED outcome in the outbox; idempotent via PaymentOutboxService. */
    @Transactional
    public Payment markFailed(Payment payment, String message, String pmoResultCode, String bankResponse) {
        payment.setStatus(STATUS_FAILED);
        payment.setCallbackProcessed(true);
        payment.setMessage(message);
        if (pmoResultCode != null && !pmoResultCode.isBlank()) {
            payment.setPmoResultCode(truncate(pmoResultCode, 32));
            // Also in code/operationCode so existing admin/support views show the reason.
            if (payment.getCode() == null || payment.getCode().isBlank()) {
                payment.setCode(truncate(pmoResultCode, 32));
            }
            payment.setOperationCode(truncate(pmoResultCode, 32));
        }
        if (bankResponse != null && !bankResponse.isBlank()) {
            payment.setBankResponse(truncate(bankResponse, BANK_RESPONSE_MAX_LEN));
        }
        Payment saved = paymentRepository.save(payment);
        paymentOutboxService.recordPaymentOutcome(saved);
        return saved;
    }

    /** Terminal for the callback (processed=true) but deliberately no outbox event or subscription. */
    @Transactional
    public Payment markManualReview(Payment payment, String message, KapitalOrderResponse order, String bankResponse) {
        payment.setStatus(STATUS_MANUAL_REVIEW);
        payment.setCallbackProcessed(true);
        payment.setMessage(message);
        applyBankFields(payment, order);
        if (bankResponse != null && !bankResponse.isBlank()) {
            payment.setBankResponse(truncate(bankResponse, BANK_RESPONSE_MAX_LEN));
        }
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment markRefunded(Payment payment) {
        payment.setStatus(STATUS_REFUNDED);
        return paymentRepository.save(payment);
    }

    private void applyBankFields(Payment payment, KapitalOrderResponse order) {
        if (order == null) {
            return;
        }
        String pmo = order.getPmoResultCode();
        if (pmo != null && !pmo.isBlank()) {
            payment.setPmoResultCode(truncate(pmo, 32));
        }
        String rrn = order.getRrn();
        if (rrn != null && !rrn.isBlank()) {
            payment.setRrn(rrn);
        }
        String approval = order.getApprovalCode();
        if (approval != null && !approval.isBlank()) {
            payment.setCode(truncate(approval, 32));
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
