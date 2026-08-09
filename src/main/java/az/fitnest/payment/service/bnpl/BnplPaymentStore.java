package az.fitnest.payment.service.bnpl;

import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.enums.BnplOrderStatus;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.util.PaymentPackageRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BnplPaymentStore {

    public static final String PROVIDER = "ABB_BNPL";
    public static final String TYPE = "ABB_BNPL";

    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;

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
        payment.setBankResponse(writeMeta(fin, phone, term, productName, 0, null));
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment markSubmitted(Payment payment, Long abbOrderId, String abbStatus, String message) {
        payment.setOrderId(String.valueOf(abbOrderId));
        payment.setCode(abbStatus != null ? abbStatus : BnplOrderStatus.INIT.name());
        payment.setMessage(message);
        BnplOrderStatus status = BnplOrderStatus.from(abbStatus);
        payment.setStatus(status != null ? status.toPaymentStatus() : "PENDING_USER_ACTION");
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment markFailed(Payment payment, String message, String operationCode, String bankResponse) {
        payment.setStatus("FAILED");
        payment.setMessage(message);
        if (operationCode != null) {
            payment.setOperationCode(operationCode);
        }
        if (bankResponse != null) {
            payment.setBankResponse(truncate(bankResponse));
        }
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment applyAbbStatus(Payment payment,
                                  BnplOrderStatus abbStatus,
                                  Integer partialReverseCount,
                                  String rawPayload) {
        if (abbStatus == null) {
            return payment;
        }
        payment.setCode(abbStatus.name());
        payment.setStatus(abbStatus.toPaymentStatus());
        payment.setMessage(abbStatus.toUserMessageAz());
        if (rawPayload != null) {
            payment.setBankTransaction(truncate(rawPayload));
        }

        Map<String, Object> meta = readMeta(payment.getBankResponse());
        if (partialReverseCount != null) {
            meta.put("partialReverseCount", partialReverseCount);
        }
        meta.put("abbStatus", abbStatus.name());
        payment.setBankResponse(writeMetaMap(meta));

        if (abbStatus.isTerminal()) {
            payment.setCallbackProcessed(true);
        }
        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByReference(String reference) {
        return paymentRepository.findByTransactionId(reference);
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByAbbOrderId(String abbOrderId) {
        return paymentRepository.findByOrderId(abbOrderId);
    }

    @Transactional
    public Payment save(Payment payment) {
        return paymentRepository.save(payment);
    }

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
                             Integer partialReverseCount, String abbStatus) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fin", maskFin(fin));
        meta.put("phone", phone);
        meta.put("term", term);
        meta.put("productName", productName);
        meta.put("partialReverseCount", partialReverseCount != null ? partialReverseCount : 0);
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

    private static String maskFin(String fin) {
        if (fin == null || fin.length() < 3) {
            return fin;
        }
        return fin.substring(0, 2) + "***" + fin.substring(fin.length() - 1);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }
}
