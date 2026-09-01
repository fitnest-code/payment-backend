package az.fitnest.payment.service.bob;

import az.fitnest.payment.dto.bob.BobOrderStatusResponse;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.util.PaymentPackageRef;
import az.fitnest.payment.util.CardMaskUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Persists and updates BOB payment rows only — no bank I/O or side effects.
 */
@Component
@RequiredArgsConstructor
public class BobPaymentStore {

    public static final String PROVIDER_BOB = "BOB";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    private static final int BANK_RESPONSE_MAX_LEN = 4000;

    private final PaymentRepository paymentRepository;

    public String buildPackageDescription(Long packageId, Long optionId, String requestDescription) {
        return PaymentPackageRef.appendToDescription(requestDescription, packageId, optionId);
    }

    @Transactional
    public Payment createPending(Long userId,
                                 String transactionId,
                                 Double amount,
                                 String currency,
                                 String description,
                                 boolean autoPaymentEnabled,
                                 String cardId,
                                 String cardMask) {
        return createPending(userId, transactionId, amount, currency, description,
                autoPaymentEnabled, cardId, cardMask, "BOB_PAYMENT");
    }

    @Transactional
    public Payment createPending(Long userId,
                                 String transactionId,
                                 Double amount,
                                 String currency,
                                 String description,
                                 boolean autoPaymentEnabled,
                                 String cardId,
                                 String cardMask,
                                 String type) {
        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setProvider(PROVIDER_BOB);
        payment.setTransactionId(transactionId);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setStatus(STATUS_PENDING);
        payment.setDescription(description);
        payment.setCallbackProcessed(false);
        payment.setAutoPaymentEnabled(autoPaymentEnabled);
        payment.setType(type != null && !type.isBlank() ? type : "BOB_PAYMENT");
        if (cardId != null) {
            payment.setCardId(cardId);
        }
        if (cardMask != null) {
            payment.setCardMask(CardMaskUtil.toLast4(cardMask));
        }
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment savePayment(Payment payment) {
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderIdOrTransactionId(String orderId, String transactionId) {
        if (orderId != null && !orderId.isBlank()) {
            Optional<Payment> byOrder = paymentRepository.findByOrderId(orderId);
            if (byOrder.isPresent()) {
                return byOrder;
            }
        }
        if (transactionId != null && !transactionId.isBlank()) {
            return paymentRepository.findByTransactionId(transactionId);
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    @Transactional
    public Payment save(Payment payment) {
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment markRegistered(Payment payment, String orderId, String formUrl) {
        payment.setOrderId(orderId);
        payment.setRedirectUrl(formUrl);
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment markFailed(Payment payment, String message, String operationCode, String bankResponse) {
        payment.setStatus(STATUS_FAILED);
        payment.setMessage(message);
        if (operationCode != null && !operationCode.isBlank()) {
            payment.setOperationCode(operationCode);
        }
        if (bankResponse != null && !bankResponse.isBlank()) {
            payment.setBankResponse(truncate(bankResponse));
        }
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment markSuccess(Payment payment, BobOrderStatusResponse statusResponse) {
        payment.setStatus(STATUS_SUCCESS);
        applyBankCardFields(payment, statusResponse);
        payment.setCallbackProcessed(true);
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment markRefunded(Payment payment) {
        payment.setStatus(STATUS_REFUNDED);
        return paymentRepository.save(payment);
    }

    public void applyBankCardFields(Payment payment, BobOrderStatusResponse statusResponse) {
        if (statusResponse == null) {
            return;
        }
        statusResponse.flattenBankPayload();

        String rrn = statusResponse.getRrn();
        if (rrn == null || rrn.isBlank()) {
            rrn = statusResponse.getAuthRefNum();
        }
        if (rrn != null && !rrn.isBlank()) {
            payment.setRrn(rrn);
        }
        if (statusResponse.getPan() != null && !statusResponse.getPan().isBlank()) {
            payment.setCardMask(CardMaskUtil.toLast4(statusResponse.getPan()));
        }
        if (statusResponse.getCardholderName() != null && !statusResponse.getCardholderName().isBlank()) {
            payment.setCardName(statusResponse.getCardholderName());
        }
        if (statusResponse.getApprovalCode() != null && !statusResponse.getApprovalCode().isBlank()) {
            payment.setCode(statusResponse.getApprovalCode());
        }
    }

    private static String truncate(String value) {
        if (value.length() <= BANK_RESPONSE_MAX_LEN) {
            return value;
        }
        return value.substring(0, BANK_RESPONSE_MAX_LEN);
    }
}
