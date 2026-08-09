package az.fitnest.payment.service;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.abb.bnpl.AbbBnplProperties;
import az.fitnest.payment.client.abb.bnpl.AbbBnplRestClient;
import az.fitnest.payment.dto.abb.bnpl.*;
import az.fitnest.payment.exception.BnplMaintenanceException;
import az.fitnest.payment.exception.BnplPaymentException;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.model.enums.BnplOrderStatus;
import az.fitnest.payment.service.bnpl.BnplPaymentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * ABB BNPL orchestration: submit order, callback handling, status fallback, reverse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BnplIntegrationService {

    private static final Set<Integer> ALLOWED_TERMS = Set.of(1, 3, 4, 6, 9, 12, 18, 24);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AbbBnplProperties properties;
    private final AbbBnplRestClient restClient;
    private final BnplPaymentStore paymentStore;
    private final PaymentSubscriptionService paymentSubscriptionService;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private void checkMaintenance() {
        if (properties.isMaintenanceMode()) {
            throw new BnplMaintenanceException(
                    "Hazırda ABB BNPL ödəniş sistemində texniki işlər aparılır. Xidmət tezliklə istifadəyə veriləcək");
        }
    }

    @Transactional
    public BnplInitResponse initiate(Long userId, BnplInitRequest request) {
        checkMaintenance();
        validateInitRequest(request);

        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(
                request.getPackageId(), request.getOptionId());
        double originalAmount = priceCurrency.amount;
        BigDecimal coins = request.getCoinsToUse() != null ? request.getCoinsToUse() : BigDecimal.ZERO;
        if (coins.compareTo(BigDecimal.ZERO) < 0) {
            throw new BnplPaymentException("BNPL_INVALID_COINS", "Coin məbləği mənfi ola bilməz");
        }
        double netAmount = Math.max(0, originalAmount - coins.doubleValue());
        if (netAmount < 100) {
            throw new BnplPaymentException("BNPL_AMOUNT_TOO_LOW",
                    "BNPL məbləği minimum 100 AZN olmalıdır (coin endirimindən sonra)");
        }
        if (netAmount > 5000) {
            throw new BnplPaymentException("BNPL_AMOUNT_TOO_HIGH",
                    "BNPL məbləği maksimum 5,000 AZN ola bilər");
        }

        String productName = resolveProductName(request.getPackageId(), request.getOptionId());
        String reference = generateReference();
        String description = paymentStore.buildDescription(
                request.getPackageId(), request.getOptionId(), productName);
        String phone = normalizePhone(request.getPhone());
        String fin = request.getFin().trim().toUpperCase(Locale.ROOT);
        String termValue = formatTerm(request.getTerm());

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
                .term(termValue)
                .phone(phone)
                .price(netAmount)
                .productName(productName)
                .reference(reference)
                .build();

        try {
            BnplAbbSubmitResponse abbResponse = restClient.submitOrder(abbRequest);
            if (abbResponse.getOrderId() == null) {
                paymentStore.markFailed(payment, "ABB orderId qayıtmadı", "NO_ORDER_ID",
                        String.valueOf(abbResponse));
                throw new BnplPaymentException("BNPL_NO_ORDER_ID", "ABB sifariş ID qayıtmadı");
            }

            String abbStatus = abbResponse.getStatus() != null ? abbResponse.getStatus() : "INIT";
            paymentStore.markSubmitted(payment, abbResponse.getOrderId(), abbStatus, abbResponse.getMessage());

            log.info("[BNPL] Order submitted reference={} abbOrderId={} status={}",
                    reference, abbResponse.getOrderId(), abbStatus);

            return BnplInitResponse.success(
                    reference,
                    String.valueOf(abbResponse.getOrderId()),
                    abbStatus,
                    abbResponse.getMessage() != null
                            ? abbResponse.getMessage()
                            : BnplOrderStatus.INIT.toUserMessageAz(),
                    netAmount,
                    request.getTerm());
        } catch (BnplPaymentException e) {
            paymentStore.markFailed(payment, e.getMessage(), e.getErrorCode(), e.getMessage());
            throw e;
        }
    }

    /**
     * ABB callback — must return quickly. Idempotent via X-ABB-Callback-Id.
     */
    @Transactional
    public void processCallback(BnplCallbackPayload payload,
                                String callbackId,
                                String basicAuthHeader,
                                String signature,
                                String rawBody) {
        validateCallbackAuth(basicAuthHeader);
        verifyOptionalSignature(signature, rawBody);

        if (callbackId != null && !callbackId.isBlank()) {
            String dedupeKey = "bnpl:callback:" + callbackId;
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(dedupeKey, "1", Duration.ofDays(7));
            if (Boolean.FALSE.equals(first)) {
                log.info("[BNPL][Callback] Duplicate callbackId={} ignored", callbackId);
                return;
            }
        }

        if (payload == null || payload.getOrderId() == null) {
            throw new BnplPaymentException("BNPL_CALLBACK_INVALID", "Callback orderId məcburidir");
        }

        Payment payment = paymentStore.findByAbbOrderId(String.valueOf(payload.getOrderId()))
                .orElseThrow(() -> new BnplPaymentException("BNPL_ORDER_NOT_FOUND",
                        "Lokal BNPL sifarişi tapılmadı: " + payload.getOrderId()));

        BnplOrderStatus previous = BnplOrderStatus.from(payment.getCode());
        BnplOrderStatus next = BnplOrderStatus.from(payload.getStatus());
        if (next == null) {
            log.warn("[BNPL][Callback] Unknown status={} orderId={}", payload.getStatus(), payload.getOrderId());
            return;
        }

        // Do not regress from SUCCESS/REVERSED on out-of-order callbacks
        if (previous != null && previous.isTerminal() && previous != next
                && previous.isSuccess() && next != BnplOrderStatus.REVERSED
                && next != BnplOrderStatus.CLOSED) {
            log.warn("[BNPL][Callback] Ignoring status regression {} → {} orderId={}",
                    previous, next, payload.getOrderId());
            return;
        }

        boolean alreadyCompleted = "SUCCESS".equals(payment.getStatus());
        paymentStore.applyAbbStatus(payment, next, payload.getPartialReverseCount(), rawBody);

        if (next.isSuccess() && !alreadyCompleted) {
            paymentSubscriptionService.assignFromPaymentDescription(payment, false);
            log.info("[BNPL][Callback] COMPLETED → subscription assigned orderId={}", payload.getOrderId());
        }

        log.info("[BNPL][Callback] Processed orderId={} status={} partialReverseCount={}",
                payload.getOrderId(), next, payload.getPartialReverseCount());
    }

    @Transactional
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
            long abbOrderId = Long.parseLong(payment.getOrderId());
            BnplAbbOrderDetail detail = restClient.getOrder(abbOrderId);
            BnplOrderStatus remote = BnplOrderStatus.from(detail.getStatus());
            if (remote != null) {
                boolean alreadyCompleted = "SUCCESS".equals(payment.getStatus());
                paymentStore.applyAbbStatus(payment, remote, null, writeSafe(detail));
                if (remote.isSuccess() && !alreadyCompleted) {
                    paymentSubscriptionService.assignFromPaymentDescription(payment, false);
                }
                local = remote;
            }
        } catch (BnplPaymentException e) {
            log.warn("[BNPL][Status] Fallback poll failed for {}: {}", referenceOrAbbOrderId, e.getMessage());
        }

        return toStatusResponse(payment, local);
    }

    @Transactional(readOnly = true)
    public BnplStatusResponse getLocalStatus(String referenceOrAbbOrderId) {
        Payment payment = findPayment(referenceOrAbbOrderId)
                .orElseThrow(() -> new BnplPaymentException("BNPL_ORDER_NOT_FOUND",
                        "BNPL sifarişi tapılmadı"));
        return toStatusResponse(payment, BnplOrderStatus.from(payment.getCode()));
    }

    @Transactional
    public void fullReverse(String referenceOrAbbOrderId) {
        checkMaintenance();
        Payment payment = findPayment(referenceOrAbbOrderId)
                .orElseThrow(() -> new BnplPaymentException("BNPL_ORDER_NOT_FOUND",
                        "BNPL sifarişi tapılmadı"));
        if (!"SUCCESS".equals(payment.getStatus())
                && BnplOrderStatus.from(payment.getCode()) != BnplOrderStatus.COMPLETED) {
            throw new BnplPaymentException("BNPL_INVALID_STATUS",
                    "Yalnız tamamlanmış BNPL sifarişi geri qaytarıla bilər");
        }
        long abbOrderId = Long.parseLong(payment.getOrderId());
        restClient.fullReverse(abbOrderId);
        paymentStore.applyAbbStatus(payment, BnplOrderStatus.REVERSED, null, "full-reverse");
        log.info("[BNPL] Full reverse done abbOrderId={}", abbOrderId);
    }

    @Transactional
    public void partialReverse(String referenceOrAbbOrderId, double amount) {
        checkMaintenance();
        if (amount < 1) {
            throw new BnplPaymentException("BNPL_INVALID_AMOUNT", "Partial reverse minimum 1 AZN olmalıdır");
        }
        Payment payment = findPayment(referenceOrAbbOrderId)
                .orElseThrow(() -> new BnplPaymentException("BNPL_ORDER_NOT_FOUND",
                        "BNPL sifarişi tapılmadı"));
        long abbOrderId = Long.parseLong(payment.getOrderId());
        restClient.partialReverse(abbOrderId, amount);
        Integer count = paymentStore.readPartialReverseCount(payment);
        paymentStore.applyAbbStatus(payment, BnplOrderStatus.REVERSED, count + 1,
                "partial-reverse:" + amount);
        log.info("[BNPL] Partial reverse done abbOrderId={} amount={}", abbOrderId, amount);
    }

    @Transactional(readOnly = true)
    public List<Integer> getSupportedTerms() {
        List<Integer> configured = properties.getActiveTerms();
        if (configured == null || configured.isEmpty()) {
            return List.copyOf(ALLOWED_TERMS);
        }
        return configured.stream().filter(ALLOWED_TERMS::contains).toList();
    }

    private void validateInitRequest(BnplInitRequest request) {
        if (request.getFin() == null || request.getFin().isBlank() || request.getFin().trim().length() > 7) {
            throw new BnplPaymentException("BNPL_INVALID_FIN", "FIN maksimum 7 simvol olmalıdır");
        }
        if (request.getTerm() == null || !ALLOWED_TERMS.contains(request.getTerm())) {
            throw new BnplPaymentException("BNPL_INVALID_TERM",
                    "Dəstəklənməyən kredit müddəti. İcazə verilənlər: " + ALLOWED_TERMS);
        }
        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new BnplPaymentException("BNPL_INVALID_PHONE", "Telefon nömrəsi məcburidir");
        }
    }

    /**
     * Doc sample uses "3 ay"; enum values are numeric. We send "{n} ay" for month terms
     * and numeric string for special codes 1 / 4 until ABB clarifies the exact format.
     */
    private String formatTerm(Integer term) {
        if (term == null) {
            return null;
        }
        if (term == 1 || term == 4) {
            return String.valueOf(term);
        }
        return term + " ay";
    }

    private String normalizePhone(String phone) {
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+994")) {
            return digits.substring(4);
        }
        if (digits.startsWith("994") && digits.length() > 10) {
            return digits.substring(3);
        }
        if (digits.startsWith("0") && digits.length() == 10) {
            return digits.substring(1);
        }
        return digits.replace("+", "");
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
                .partialReverseCount(paymentStore.readPartialReverseCount(payment))
                .build();
    }

    private void validateCallbackAuth(String authorizationHeader) {
        String expectedUser = properties.getCallbackUsername();
        String expectedPass = properties.getCallbackPassword();
        if (isBlank(expectedUser) || isBlank(expectedPass)) {
            log.warn("[BNPL][Callback] Callback credentials not configured — rejecting");
            throw new BnplPaymentException("BNPL_CALLBACK_UNAUTHORIZED", "Callback credentials konfiqurasiya olunmayıb");
        }
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw new BnplPaymentException("BNPL_CALLBACK_UNAUTHORIZED", "Basic Auth tələb olunur");
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorizationHeader.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            if (idx < 0) {
                throw new BnplPaymentException("BNPL_CALLBACK_UNAUTHORIZED", "Etibarsız Basic Auth");
            }
            String user = decoded.substring(0, idx);
            String pass = decoded.substring(idx + 1);
            if (!MessageDigest.isEqual(user.getBytes(StandardCharsets.UTF_8), expectedUser.getBytes(StandardCharsets.UTF_8))
                    || !MessageDigest.isEqual(pass.getBytes(StandardCharsets.UTF_8), expectedPass.getBytes(StandardCharsets.UTF_8))) {
                throw new BnplPaymentException("BNPL_CALLBACK_UNAUTHORIZED", "Basic Auth uğursuz");
            }
        } catch (BnplPaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new BnplPaymentException("BNPL_CALLBACK_UNAUTHORIZED", "Basic Auth parse xətası");
        }
    }

    /**
     * X-Signature HMAC verification when abb-bnpl.callback-hmac-secret is set.
     * Canonical string pending ABB clarification — currently HMAC-SHA256(rawBody).
     */
    private void verifyOptionalSignature(String signatureHeader, String rawBody) {
        String secret = properties.getCallbackHmacSecret();
        if (isBlank(secret)) {
            return;
        }
        if (isBlank(signatureHeader) || isBlank(rawBody)) {
            throw new BnplPaymentException("BNPL_CALLBACK_SIGNATURE", "X-Signature yoxdur");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
            String provided = signatureHeader.trim();
            if (provided.startsWith("sha256=")) {
                provided = provided.substring(7);
            }
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    provided.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
                throw new BnplPaymentException("BNPL_CALLBACK_SIGNATURE", "X-Signature doğrulaması uğursuz");
            }
        } catch (BnplPaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new BnplPaymentException("BNPL_CALLBACK_SIGNATURE", "X-Signature yoxlanıla bilmədi");
        }
    }

    private String writeSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
