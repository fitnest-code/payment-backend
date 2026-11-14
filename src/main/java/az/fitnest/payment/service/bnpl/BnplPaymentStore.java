package az.fitnest.payment.service.bnpl;

import az.fitnest.payment.event.PaymentOutboxService;
import az.fitnest.payment.model.entity.AbbBnplCallbackEvent;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.enums.BnplOrderStatus;
import az.fitnest.payment.repository.AbbBnplCallbackEventRepository;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.util.PaymentPackageRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence for ABB BNPL payments and callbacks.
 *
 * <p>All mutating methods load the row with a pessimistic lock and take an id, never a
 * detached entity: the submit call to ABB happens <b>outside</b> any transaction and the
 * entity could otherwise be stale when the response arrives.</p>
 */
@Component
@RequiredArgsConstructor
public class BnplPaymentStore {

    public static final String PROVIDER = "ABB_BNPL";
    public static final String TYPE = "ABB_BNPL";

    public static final String STATUS_PENDING = "PENDING_USER_ACTION";
    public static final String STATUS_SUBMIT_UNKNOWN = "SUBMIT_UNKNOWN";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_REVERSED = "REVERSED";

    private final PaymentRepository paymentRepository;
    private final AbbBnplCallbackEventRepository callbackEventRepository;
    private final ObjectMapper objectMapper;
    private final PaymentOutboxService paymentOutboxService;

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public Optional<Payment> findById(Long id) {
        return paymentRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByReference(String reference) {
        return paymentRepository.findByTransactionId(reference);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByAbbOrderId(String abbOrderId) {
        return paymentRepository.findByOrderId(abbOrderId);
    }

    /**
     * Idempotency: an unfinished order for the same user/package/option created inside the
     * recovery window. ABB cannot look an order up by reference, so we must not create a
     * second one — it would answer "Order already exists" and the customer would be stuck.
     */
    @Transactional(readOnly = true)
    public Optional<Payment> findRecoverable(Long userId, Long packageId, Long optionId, int windowMinutes) {
        if (userId == null || packageId == null || optionId == null) {
            return Optional.empty();
        }
        LocalDateTime since = LocalDateTime.now().minusMinutes(Math.max(1, windowMinutes));
        return paymentRepository
                .findTop20ByProviderAndUserIdAndStatusInAndCreatedDateAfterOrderByCreatedDateDesc(
                        PROVIDER, userId, List.of(STATUS_PENDING, STATUS_SUBMIT_UNKNOWN), since)
                .stream()
                .filter(payment -> {
                    PaymentPackageRef.Ref ref = PaymentPackageRef.parse(payment.getDescription());
                    return packageId.equals(ref.packageId()) && optionId.equals(ref.optionId());
                })
                .findFirst();
    }

    /** Recent unfinished orders of a user — used to explain a duplicate-submit response. */
    @Transactional(readOnly = true)
    public List<Payment> findRecentUnfinished(Long userId, int lookbackHours) {
        if (userId == null) {
            return List.of();
        }
        return paymentRepository
                .findTop20ByProviderAndUserIdAndStatusInAndCreatedDateAfterOrderByCreatedDateDesc(
                        PROVIDER,
                        userId,
                        List.of(STATUS_PENDING, STATUS_SUBMIT_UNKNOWN),
                        LocalDateTime.now().minusHours(Math.max(1, lookbackHours)));
    }

    /** Recent unfinished order of a user. */
    @Transactional(readOnly = true)
    public Optional<Payment> findMostRecentUnfinished(Long userId, int lookbackHours) {
        return findRecentUnfinished(userId, lookbackHours).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Payment> findStale(List<String> statuses, LocalDateTime before) {
        return paymentRepository.findByProviderAndStatusInAndCreatedDateBefore(PROVIDER, statuses, before);
    }

    // ----------------------------------------------------------------- write

    @Transactional
    public Payment createPending(Long userId,
                                 String reference,
                                 Double amount,
                                 String currency,
                                 String description,
                                 String fin,
                                 String phone,
                                 Integer term,
                                 String productName,
                                 BigDecimal coinsUsed) {
        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setProvider(PROVIDER);
        payment.setType(TYPE);
        payment.setTransactionId(reference);
        payment.setAmount(amount);
        payment.setCurrency(currency != null ? currency : "AZN");
        payment.setStatus("PENDING");
        payment.setDescription(description);
        payment.setCallbackProcessed(false);
        payment.setAutoPaymentEnabled(false);
        payment.setCoinsUsed(coinsUsed != null ? coinsUsed : BigDecimal.ZERO);
        payment.setCode(BnplOrderStatus.INIT.name());
        payment.setBankResponse(writeMeta(fin, phone, term, productName, 0, null, BigDecimal.ZERO));
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment markSubmitted(Long paymentId, Long abbOrderId, String abbStatus, String message) {
        Payment payment = locked(paymentId);
        payment.setOrderId(String.valueOf(abbOrderId));
        payment.setCode(abbStatus != null ? abbStatus : BnplOrderStatus.INIT.name());
        payment.setMessage(message);
        payment.setOperationCode(null);
        BnplOrderStatus status = BnplOrderStatus.from(abbStatus);
        payment.setStatus(status != null
                ? BnplContractRules.resolvePaymentStatus(payment.getStatus(), status)
                : STATUS_PENDING);
        return paymentRepository.save(payment);
    }

    /**
     * Ambiguous submit (timeout / 5xx / duplicate). The order may exist at ABB, so this is
     * explicitly <b>not</b> {@code FAILED} — it stays pollable and is picked up by the
     * reconciliation job.
     */
    @Transactional
    public Payment markSubmitUnknown(Long paymentId, String message, String operationCode, String detail) {
        Payment payment = locked(paymentId);
        payment.setStatus(STATUS_SUBMIT_UNKNOWN);
        payment.setMessage(message);
        payment.setOperationCode(operationCode);
        if (detail != null) {
            payment.setBankTransaction(truncate(detail));
        }
        return paymentRepository.save(payment);
    }

    /** Definitive rejection by ABB (invalid FIN / phone / term, customer not found). */
    @Transactional
    public Payment markRejected(Long paymentId, String message, String operationCode, String detail) {
        Payment payment = locked(paymentId);
        payment.setStatus(STATUS_FAILED);
        payment.setMessage(message);
        if (operationCode != null) {
            payment.setOperationCode(operationCode);
        }
        if (detail != null) {
            payment.setBankTransaction(truncate(detail));
        }
        Payment saved = paymentRepository.save(payment);
        paymentOutboxService.recordPaymentOutcome(saved);
        return saved;
    }

    public record StatusUpdate(Payment payment, String previousPaymentStatus, boolean changed) {
    }

    @Transactional
    public StatusUpdate applyStatus(Long paymentId,
                                    BnplOrderStatus next,
                                    String rawPayload) {
        Payment payment = locked(paymentId);
        BnplOrderStatus previous = BnplOrderStatus.from(payment.getCode());
        String previousPaymentStatus = payment.getStatus();
        BnplOrderStatus resolved = BnplContractRules.resolveStatus(previous, next);
        if (resolved == null) {
            return new StatusUpdate(payment, previousPaymentStatus, false);
        }

        String nextPaymentStatus = BnplContractRules.resolvePaymentStatus(previousPaymentStatus, resolved);
        boolean changed = !Objects.equals(previousPaymentStatus, nextPaymentStatus)
                || !Objects.equals(payment.getCode(), resolved.name());

        payment.setCode(resolved.name());
        payment.setStatus(nextPaymentStatus);
        payment.setMessage(resolved.toUserMessageAz());
        if (rawPayload != null) {
            payment.setBankTransaction(truncate(rawPayload));
        }

        Map<String, Object> meta = readMeta(payment.getBankResponse());
        meta.put("abbStatus", resolved.name());
        payment.setBankResponse(writeMetaMap(meta));

        if (resolved.isTerminal()) {
            payment.setCallbackProcessed(true);
        }
        return new StatusUpdate(paymentRepository.save(payment), previousPaymentStatus, changed);
    }

    /**
     * Partial reversal: the order stays {@code COMPLETED} until the cumulative amount
     * reaches the full order amount (ABB contract rule).
     */
    @Transactional
    public Payment recordPartialReversal(Long paymentId,
                                         int count,
                                         BigDecimal reversedAmount,
                                         String rawPayload) {
        Payment payment = locked(paymentId);
        Map<String, Object> meta = readMeta(payment.getBankResponse());
        meta.put("partialReverseCount", count);
        meta.put("reversedAmount", reversedAmount != null ? reversedAmount : BigDecimal.ZERO);
        meta.put("abbStatus", BnplOrderStatus.COMPLETED.name());
        payment.setBankResponse(writeMetaMap(meta));
        if (rawPayload != null) {
            payment.setBankTransaction(truncate(rawPayload));
        }
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment recordReversal(Long paymentId, BigDecimal reversedAmount, String rawPayload) {
        Payment payment = locked(paymentId);
        Map<String, Object> meta = readMeta(payment.getBankResponse());
        meta.put("abbStatus", BnplOrderStatus.REVERSED.name());
        meta.put("reversedAmount", reversedAmount != null ? reversedAmount : payment.getAmount());
        payment.setBankResponse(writeMetaMap(meta));
        payment.setCode(BnplOrderStatus.REVERSED.name());
        payment.setStatus(STATUS_REVERSED);
        payment.setMessage(BnplOrderStatus.REVERSED.toUserMessageAz());
        payment.setCallbackProcessed(true);
        if (rawPayload != null) {
            payment.setBankTransaction(truncate(rawPayload));
        }
        Payment saved = paymentRepository.save(payment);
        // The reversal event is emitted by PaymentSubscriptionService#onPaymentReversed
        // (as PAYMENT_REVERSED, not PAYMENT_FAILED).
        return saved;
    }

    @Transactional
    public Payment save(Payment payment) {
        return paymentRepository.save(payment);
    }

    // -------------------------------------------------------------- callbacks

    @Transactional
    public AbbBnplCallbackEvent recordCallbackEvent(String callbackId,
                                                    Long abbOrderId,
                                                    String reference,
                                                    String status,
                                                    String payload,
                                                    boolean signatureValid,
                                                    String result) {
        AbbBnplCallbackEvent event = new AbbBnplCallbackEvent();
        event.setCallbackId(callbackId);
        event.setAbbOrderId(abbOrderId != null ? String.valueOf(abbOrderId) : null);
        event.setReference(reference);
        event.setStatus(status);
        event.setPayload(truncate(payload));
        event.setSignatureValid(signatureValid);
        event.setProcessingResult(result != null && result.length() > 512 ? result.substring(0, 512) : result);
        event.setProcessed(result != null && result.startsWith("APPLIED"));
        if (event.getProcessed()) {
            event.setProcessedAt(LocalDateTime.now());
        }
        return callbackEventRepository.save(event);
    }

    @Transactional
    public void markCallbackEventProcessed(AbbBnplCallbackEvent event, String result) {
        event.setProcessed(true);
        event.setProcessedAt(LocalDateTime.now());
        event.setProcessingResult(result != null && result.length() > 512 ? result.substring(0, 512) : result);
        callbackEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<AbbBnplCallbackEvent> findUnprocessedCallbacks() {
        return callbackEventRepository.findTop50ByProcessedFalseOrderByReceivedAtAsc();
    }

    // ------------------------------------------------------------------ meta

    public String buildDescription(Long packageId, Long optionId, String productName) {
        String base = productName != null ? productName : "FitNest BNPL";
        return PaymentPackageRef.appendToDescription(base, packageId, optionId);
    }

    public Integer readPartialReverseCount(Payment payment) {
        Object value = readMeta(payment.getBankResponse()).get("partialReverseCount");
        if (value instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    public BigDecimal readReversedAmount(Payment payment) {
        Object value = readMeta(payment.getBankResponse()).get("reversedAmount");
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (value instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException ignored) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    public Integer readTerm(Payment payment) {
        Object value = readMeta(payment.getBankResponse()).get("term");
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMeta(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeMeta(String fin, String phone, Integer term, String productName,
                             Integer partialReverseCount, String abbStatus, BigDecimal reversedAmount) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fin", maskFin(fin));
        // PII: keep only the shape needed for support calls.
        meta.put("phone", maskPhone(phone));
        meta.put("term", term);
        meta.put("productName", productName);
        meta.put("partialReverseCount", partialReverseCount != null ? partialReverseCount : 0);
        meta.put("reversedAmount", reversedAmount != null ? reversedAmount : BigDecimal.ZERO);
        if (abbStatus != null) {
            meta.put("abbStatus", abbStatus);
        }
        return writeMetaMap(meta);
    }

    private String writeMetaMap(Map<String, Object> meta) {
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Payment locked(Long paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalStateException("BNPL payment not found: " + paymentId));
    }

    private static String maskFin(String fin) {
        if (fin == null || fin.length() < 3) {
            return fin;
        }
        return fin.substring(0, 2) + "***" + fin.substring(fin.length() - 1);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 4) + "***" + phone.substring(phone.length() - 3);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }
}
