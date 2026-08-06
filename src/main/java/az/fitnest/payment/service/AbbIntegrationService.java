package az.fitnest.payment.service;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.abb.AbbProperties;
import az.fitnest.payment.client.abb.AbbSigner;
import az.fitnest.payment.dto.abb.*;
import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.PaymentRepository;
import az.fitnest.payment.util.CardBrandDetector;
import az.fitnest.payment.util.PaymentPackageRef;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URLEncoder;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * ABB (Azericard) E-Commerce Gateway inteqrasiya servisi.
 *
 * <h2>Ödəniş Axını</h2>
 * <ol>
 *   <li>Frontend {@code POST /payment/abb/init} çağırır</li>
 *   <li>Servis Gateway URL-i + imzalı parametrləri qurur, {@code redirectUrl} qaytarır</li>
 *   <li>İstifadəçi Azericard ödəniş səhifəsinə yönləndirilir</li>
 *   <li>Ödəniş tamamlandıqdan sonra bank {@code BACKREF} URL-inə POST edir</li>
 *   <li>{@link #processCallback} imzanı yoxlayır, Payment entity-ni yeniləyir</li>
 *   <li>Uğurlu ödənişdə abunəlik gRPC ilə təyin edilir</li>
 * </ol>
 *
 * <h2>Arxitektura Qeydləri</h2>
 * <ul>
 *   <li>İdempotentlik: {@code orderId} Redis TTL əsasında 24 saat saxlanılır</li>
 *   <li>Callback yenidən emal qorunması: {@code callbackProcessed} bayrağı istifadə edilir</li>
 *   <li>Bütün bank sahə sıralamaları spec §2.2.1 əsasında: AMOUNT, CURRENCY,
 *       TERMINAL, TRTYPE, TIMESTAMP, NONCE, MERCH_URL</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AbbIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(AbbIntegrationService.class);

    /** ABB TRTYPE: birbaşa avtorizasiya */
    private static final String TRTYPE_DIRECT = "1";

    /** ACTION=0 → tranzaksiya uğurla tamamlandı */
    private static final String ACTION_SUCCESS = "0";

    /** RC=00 → ISO-8583 "Approved" */
    private static final String RC_APPROVED = "00";

    /** Payment provider adı */
    private static final String PROVIDER_ABB = "ABB";

    private final AbbProperties abbProperties;
    private final AbbSigner abbSigner;
    private final PaymentRepository paymentRepository;
    private final StringRedisTemplate redisTemplate;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;
    private final PaymentSubscriptionService paymentSubscriptionService;
    private final UserDisplayNameResolver userDisplayNameResolver;
    private final az.fitnest.payment.client.UserGrpcClient userGrpcClient;

    /** Paylaşılan HTTP client instance (thread-safe, yenidən istifadə edilir) */
    private static final java.net.http.HttpClient HTTP_CLIENT =
            java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

    /** OrderId üçün SecureRandom instance (thread-safe) */
    private static final SecureRandom ORDER_RANDOM = new SecureRandom();

    // ══════════════════════════════════════════════════════════════════════════
    // İctimai metodlar – Ödəniş başlatma
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Taksit ödənişi üçün Azericard Gateway URL-ini qurur.
     *
     * <p>Paket qiyməti gRPC vasitəsilə alınır, unikal orderId generasiya edilir,
     * MAC imzalanır, nəticə frontend-ə {@code redirectUrl} kimi qaytarılır.</p>
     *
     * @param userId     daxil olmuş istifadəçi ID-si
     * @param packageId  abunəlik paketi ID-si
     * @param optionId   paket seçimi ID-si
     * @param installment taksit seçimi (null = taksitsiz)
     * @return {@link AbbInitiateResponse} redirect URL və orderId ilə
     */
    @Transactional
    public AbbInitiateResponse initiateInstallmentPayment(
            Long userId,
            Long packageId,
            Long optionId,
            AbbInstallmentOption installment) {

        log.info("[ABB][Init] userId={}, packageId={}, optionId={}, installment={}",
                userId, packageId, optionId, installment);

        // 1. Qiyməti gRPC vasitəsilə al
        var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(packageId, optionId);
        double amount = priceCurrency.amount;
        String currency = priceCurrency.currency != null ? priceCurrency.currency : abbProperties.getDefaultCurrency();

        // 2. Unikal orderId yarat (8 rəqəmli sıralı format – spec: 6-32 rəqəm)
        String orderId = generateOrderId();
        String description = buildDescription(packageId, optionId);

        // 3. Timestamp + Nonce yarat
        String timestamp = abbSigner.generateTimestamp();
        String nonce     = abbSigner.generateNonce();
        String amountStr = formatAmount(amount);

        // 4. MAC imzası hesabla (spec §2.2.1 sahə sırası: AMOUNT, CURRENCY, TERMINAL,
        //    TRTYPE, TIMESTAMP, NONCE, MERCH_URL)
        String macSource = abbSigner.buildMacSource(
                amountStr,
                currency,
                abbProperties.getTerminalId(),
                TRTYPE_DIRECT,
                timestamp,
                nonce,
                abbProperties.getMerchantUrl()
        );
        String pSign = abbSigner.sign(macSource, abbProperties.getPrivateKey());

        // 5. Azericard-a göndəriləcək form parametrlərini qur
        AbbPaymentRequest.AbbPaymentRequestBuilder reqBuilder = AbbPaymentRequest.builder()
                .amount(amountStr)
                .currency(currency)
                .order(orderId)
                .trtype(TRTYPE_DIRECT)
                .backref(abbProperties.getCallbackUrl())
                .timestamp(timestamp)
                .nonce(nonce)
                .pSign(pSign)
                .terminal(abbProperties.getTerminalId())
                .merchName(abbProperties.getMerchantName())
                .merchUrl(abbProperties.getMerchantUrl())
                .email(abbProperties.getMerchantEmail())
                .country(abbProperties.getCountryCode())
                .merchGmt(abbProperties.getMerchantGmt())
                .desc(description)
                .lang(abbProperties.getDefaultLanguage());

        // 6. Taksit parametrini əlavə et
        AbbInstallmentOption inst = (installment != null) ? installment : AbbInstallmentOption.NONE;
        reqBuilder.acqInstPayin(inst.getParamValue());
        log.info("[ABB][Init] Installment parameter (ACQ_INST_PAYIN) set to: {}", inst.getParamValue());

        AbbPaymentRequest request = reqBuilder.build();

        // 7. Gateway URL-ini GET parametrləri kimi qur
        String redirectUrl = buildGatewayRedirectUrl(request);
        log.info("[ABB][Init] Gateway redirect URL prepared for orderId={}", orderId);

        // 8. Pending Payment entity-ni saxla
        Payment payment = createPendingPayment(orderId, amount, currency, userId, description, inst);
        paymentRepository.save(payment);
        log.info("[ABB][Init] Pending payment saved: id={}, orderId={}", payment.getId(), orderId);

        // 9. userId-ni Redis-ə yaz (callback zamanı istifadə üçün)
        String redisKey = "abb-payment-user:" + orderId;
        redisTemplate.opsForValue().set(redisKey, String.valueOf(userId), 1, TimeUnit.DAYS);

        return AbbInitiateResponse.success(redirectUrl, orderId);
    }

    /**
     * Taksitsiz ödəniş üçün overloaded metod.
     */
    @Transactional
    public AbbInitiateResponse initiatePayment(Long userId, Long packageId, Long optionId) {
        return initiateInstallmentPayment(userId, packageId, optionId, AbbInstallmentOption.NONE);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // İctimai metodlar – Callback emalı
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Azericard-dan gələn BACKREF callback-ini emal edir.
     *
     * <h3>Emal ardıcıllığı</h3>
     * <ol>
     *   <li>Məcburi sahələrin mövcudluğunu yoxla</li>
     *   <li>P_SIGN imzasını Azericard açıq açarı ilə doğrula</li>
     *   <li>OrderId-yə görə Payment entity-ni tap</li>
     *   <li>Dublikat callback-ı rədd et (idempotentlik)</li>
     *   <li>Payment entity-ni yenilə</li>
     *   <li>Uğurlu ödənişdə abunəliyi gRPC ilə təyin et</li>
     * </ol>
     *
     * @param callback BACKREF URL-inə POST edilən callback parametrləri
     * @throws SecurityException  imza yoxlaması uğursuz olduqda
     * @throws IllegalArgumentException məcburi sahə çatışmadıqda
     */
    @Transactional
    public void processCallback(AbbCallbackResponse callback) {
        log.info("[ABB][Callback] Received: order={}, action={}, rc={}, rrn={}, approval={}",
                callback.getOrder(), callback.getAction(), callback.getRc(),
                callback.getRrn(), callback.getApproval());

        // ── 1. Məcburi sahə yoxlamaları ──────────────────────────────────
        validateCallbackFields(callback);

        // ── 2. P_SIGN imzasını doğrula ───────────────────────────────────
        // Spec §Geri çağırış: sahə sırası AMOUNT, TERMINAL, APPROVAL, RRN, INT_REF
        // Qeyd: callback-dəki P_SIGN boş sahə yoxlamasında '-' istifadə edilir,
        //       LAKIN uzunluğu nəzərə alınmır — spec §P_SIGN yoxlaması
        String callbackMacSource = abbSigner.buildMacSource(
                callback.getAmount(),
                callback.getTerminal(),
                callback.getApproval(),
                callback.getRrn(),
                callback.getIntRef()
        );
        boolean signatureValid = abbSigner.verify(
                callbackMacSource,
                callback.getPSign(),
                abbProperties.getPublicKey()
        );
        if (!signatureValid) {
            log.error("[ABB][Callback] P_SIGN verification FAILED for order={}", callback.getOrder());
            throw new SecurityException("error.abb_signature_verification_failed");
        }
        log.info("[ABB][Callback] P_SIGN verified for order={}", callback.getOrder());

        // ── 3. Payment entity-ni tap ──────────────────────────────────────
        Optional<Payment> optPayment = paymentRepository.findByOrderId(callback.getOrder());
        if (optPayment.isEmpty()) {
            log.warn("[ABB][Callback] No Payment found for order={}. Skipping.", callback.getOrder());
            return;
        }

        Payment payment = optPayment.get();

        // ── 4. Dublikat callback qoruması ──────────────────────────────────
        if (Boolean.TRUE.equals(payment.getCallbackProcessed())) {
            log.warn("[ABB][Callback] Already processed for order={}. Ignoring duplicate.", callback.getOrder());
            return;
        }

        // ── 5. Məbləğ uyğunluğu yoxla ────────────────────────────────────
        if (payment.getAmount() != null && callback.getAmount() != null) {
            double callbackAmount = Double.parseDouble(callback.getAmount());
            if (Math.abs(payment.getAmount() - callbackAmount) > 0.001) {
                log.error("[ABB][Callback] Amount mismatch! DB={}, callback={} for order={}",
                        payment.getAmount(), callbackAmount, callback.getOrder());
                throw new SecurityException("error.abb_amount_mismatch");
            }
        }

        // ── 6. userId-ni Redis-dən yüklə (lazım olduqda) ─────────────────
        if (payment.getUserId() == null) {
            String redisKey = "abb-payment-user:" + callback.getOrder();
            String userIdStr = redisTemplate.opsForValue().get(redisKey);
            if (userIdStr != null) {
                try {
                    payment.setUserId(Long.parseLong(userIdStr));
                    log.info("[ABB][Callback] UserId loaded from Redis: {} for order={}",
                            userIdStr, callback.getOrder());
                } catch (NumberFormatException e) {
                    log.warn("[ABB][Callback] Invalid userId in Redis for order={}: {}",
                            callback.getOrder(), userIdStr);
                }
            }
        }

        // ── 7. Payment entity-ni yenilə ──────────────────────────────────
        updatePaymentFromCallback(payment, callback);
        payment.setCallbackProcessed(true);
        paymentRepository.save(payment);
        log.info("[ABB][Callback] Payment updated: order={}, status={}", callback.getOrder(), payment.getStatus());

        // ── 8. Uğurlu ödənişdə abunəliyi təyin et ────────────────────────
        if (callback.isSuccessful()) {
            log.info("[ABB][Callback] Payment successful for order={}. Assigning subscription.", callback.getOrder());
            assignSubscriptionIfPossible(payment);
        } else {
            log.info("[ABB][Callback] Payment not successful: action={}, rc={} for order={}",
                    callback.getAction(), callback.getRc(), callback.getOrder());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Redirect URL metodları
    // ══════════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════════
    // TRTYPE=21 — Ödənişin tamamlanması (Completion / Pre-Auth Complete)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Əvvəlcədən avtorizasiya edilmiş (TRTYPE=0) ödənişi tamamlayır.
     *
     * <p>Spec §2.1.1 – TRTYPE=21 tələb edir:
     * AMOUNT, CURRENCY, ORDER, RRN, INT_REF sahələrini.</p>
     *
     * <h3>MAC sahə sırası (spec §2.2.2)</h3>
     * {@code AMOUNT → CURRENCY → TERMINAL → TRTYPE(=21) → ORDER → RRN → INT_REF}
     *
     * @param request orijinal callback-dən alınan RRN, INT_REF, ORDER məlumatları
     * @return bank cavabı
     */
    @Transactional
    public AbbTransactionActionResponse completePayment(AbbTransactionActionRequest request) {
        log.info("[ABB][TRTYPE=21] Completing payment: orderId={}, amount={}, rrn={}, intRef={}",
                request.orderId(), request.amount(), request.rrn(), request.intRef());
        return executeTransactionAction("21", request, "TRTYPE=21 Completion");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRTYPE=22 — Online Reversal (tam geri qaytarma, əməliyyat günü)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Əməliyyatın online reversal-ını icra edir (eyni bank günü).
     *
     * <p>Spec §2.2.3 – TRTYPE=22. Əməliyyat günü keçdikdən sonra
     * {@link #offlineReversal(AbbTransactionActionRequest)} istifadə edilməlidir.</p>
     *
     * <h3>MAC sahə sırası (spec §2.2.3)</h3>
     * {@code AMOUNT → CURRENCY → TERMINAL → TRTYPE(=22) → ORDER → RRN → INT_REF}
     */
    @Transactional
    public AbbTransactionActionResponse onlineReversal(AbbTransactionActionRequest request) {
        log.info("[ABB][TRTYPE=22] Online reversal: orderId={}, amount={}, rrn={}",
                request.orderId(), request.amount(), request.rrn());
        AbbTransactionActionResponse response = executeTransactionAction("22", request, "TRTYPE=22 Online Reversal");
        if ("success".equals(response.status())) {
            paymentRepository.findByOrderId(request.orderId()).ifPresent(payment -> {
                payment.setStatus("REVERSED");
                paymentRepository.save(payment);
                log.info("[ABB][TRTYPE=22] Payment status set to REVERSED for orderId={}", request.orderId());
            });
        }
        return response;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRTYPE=24 — Offline Reversal (geri qaytarma, bank günü keçdikdən sonra)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Əməliyyatın offline reversal-ını icra edir (bank günü keçdikdən sonra).
     *
     * <p>Spec §2.2.4 – TRTYPE=24. Eyni bank günü üçün
     * {@link #onlineReversal(AbbTransactionActionRequest)} istifadə edilməlidir.</p>
     *
     * <h3>MAC sahə sırası (spec §2.2.4)</h3>
     * {@code AMOUNT → CURRENCY → TERMINAL → TRTYPE(=24) → ORDER → RRN → INT_REF}
     */
    @Transactional
    public AbbTransactionActionResponse offlineReversal(AbbTransactionActionRequest request) {
        log.info("[ABB][TRTYPE=24] Offline reversal: orderId={}, amount={}, rrn={}",
                request.orderId(), request.amount(), request.rrn());
        AbbTransactionActionResponse response = executeTransactionAction("24", request, "TRTYPE=24 Offline Reversal");
        if ("success".equals(response.status())) {
            paymentRepository.findByOrderId(request.orderId()).ifPresent(payment -> {
                payment.setStatus("REFUNDED");
                paymentRepository.save(payment);
                log.info("[ABB][TRTYPE=24] Payment status set to REFUNDED for orderId={}", request.orderId());
            });
        }
        return response;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRTYPE=90 — Transaction Status Inquiry (Əməliyyat Statusu Sorğusu)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Bankdan əməliyyatın mövcud statusunu soruşur.
     *
     * <p>Spec §8.1 – TRTYPE=90. Merchant əməliyyat vaxtından etibarən
     * 24 saat ərzində status sorğusu göndərə bilər.</p>
     *
     * <h3>Sorğu parametrləri (spec §8.1)</h3>
     * {@code TRAN_TRTYPE, ORDER, TERMINAL, TRTYPE(=90), TIMESTAMP, NONCE, P_SIGN}
     *
     * <p>Qeyd: TRTYPE=90 üçün P_SIGN MAC sahə sırası TRTYPE=0/1-dən fərqlidir.
     * Bu sorğunun P_SIGN-i həmin əməliyyatın parametrləri üzərindən hesablanmır —
     * bankın öz sandbox tool-u ilə yoxlanılmalıdır.</p>
     *
     * @param orderId  sorğulanacaq orijinal əməliyyatın ORDER dəyəri
     * @param originalTrtype orijinal əməliyyatın TRTYPE-ı (məs: "1")
     * @return bank status cavabı
     */
    @Transactional
    public AbbTransactionActionResponse getTransactionStatus(String orderId, String originalTrtype) {
        log.info("[ABB][TRTYPE=90] Status inquiry: orderId={}, originalTrtype={}", orderId, originalTrtype);

        Optional<Payment> paymentOpt = paymentRepository.findByOrderId(orderId);
        if (paymentOpt.isPresent()) {
            Payment payment = paymentOpt.get();
            if (Boolean.TRUE.equals(payment.getCallbackProcessed()) || "SUCCESS".equals(payment.getStatus())) {
                if ("SUCCESS".equals(payment.getStatus())) {
                    log.info("[ABB][TRTYPE=90] Payment for orderId={} was already processed locally as SUCCESS.", orderId);
                    String maskedCard = maskCardToLast4(payment.getCardMask());
                    return buildSuccessResponse(
                            "0", // action = 0
                            payment.getCode() != null ? payment.getCode() : "00", // rc
                            payment.getOperationCode(), // approval
                            payment.getRrn(), // rrn
                            payment.getTransactionId(), // intRef
                            maskedCard, // card
                            payment
                    );
                } else {
                    log.info("[ABB][TRTYPE=90] Payment for orderId={} was already processed locally as FAILED.", orderId);
                    String maskedCard = maskCardToLast4(payment.getCardMask());
                    String action = "3";
                    if (payment.getBankResponse() != null && payment.getBankResponse().contains("ACTION=")) {
                        for (String line : payment.getBankResponse().split("\n")) {
                            if (line.trim().startsWith("ACTION=")) {
                                action = line.replace("ACTION=", "").trim();
                            }
                        }
                    }
                    return buildSuccessResponse(
                            action,
                            payment.getCode() != null ? payment.getCode() : "96", // rc
                            payment.getOperationCode(), // approval
                            payment.getRrn(), // rrn
                            payment.getTransactionId(), // intRef
                            maskedCard,
                            payment
                    );
                }
            }
        }

        String timestamp = abbSigner.generateTimestamp();
        String nonce     = abbSigner.generateNonce();

        // Spec §8.1 — TRTYPE=90 üçün MAC sahə sırası:
        // TRAN_TRTYPE, ORDER, TERMINAL, TRTYPE(=90), TIMESTAMP, NONCE
        String macSource = abbSigner.buildMacSource(
                originalTrtype,
                orderId,
                abbProperties.getTerminalId(),
                "90",
                timestamp,
                nonce
        );
        String pSign = abbSigner.sign(macSource, abbProperties.getPrivateKey());

        try {
            StringBuilder sb = new StringBuilder();
            appendParam(sb, "TRAN_TRTYPE", originalTrtype, false);
            appendParam(sb, "ORDER",       orderId,        true);
            appendParam(sb, "TERMINAL",    abbProperties.getTerminalId(), true);
            appendParam(sb, "TRTYPE",      "90",           true);
            appendParam(sb, "TIMESTAMP",   timestamp,      true);
            appendParam(sb, "NONCE",       nonce,          true);
            appendParam(sb, "P_SIGN",      pSign,          true);
            String formBody = sb.toString();

            log.info("[ABB][TRTYPE=90] Sending status request to gateway for orderId={}", orderId);

            java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(abbProperties.getGatewayUrl()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            java.net.http.HttpResponse<String> httpResponse =
                    HTTP_CLIENT.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString());

            log.info("[ABB][TRTYPE=90] Gateway response status={}, body={}",
                    httpResponse.statusCode(), httpResponse.body());

            String body = httpResponse.body();
            String action   = extractTagValue(body, "action");
            String rc       = extractTagValue(body, "rc");
            String approval = extractTagValue(body, "approval");
            String rrn      = extractTagValue(body, "rrn");
            String intRef   = extractTagValue(body, "int_ref");

            boolean responseSuccess = ACTION_SUCCESS.equals(action) && RC_APPROVED.equals(rc);

            paymentRepository.findByOrderId(orderId).ifPresent(payment -> {
                if (Boolean.TRUE.equals(payment.getCallbackProcessed())) {
                    log.info("[ABB][TRTYPE=90] Payment for orderId={} was already processed.", orderId);
                    return;
                }
                if (responseSuccess) {
                    log.info("[ABB][TRTYPE=90] Sync: Payment successful for orderId={}", orderId);
                    payment.setStatus("SUCCESS");
                    if (rrn != null && !rrn.isBlank()) {
                        payment.setRrn(rrn);
                        payment.setBankTransaction(rrn);
                    }
                    if (intRef != null && !intRef.isBlank()) {
                        payment.setTransactionId(intRef);
                    }
                    if (approval != null && !approval.isBlank()) {
                        payment.setOperationCode(approval);
                        payment.setBankResponse("APPROVAL=" + approval + "\nRC=" + rc + "\nACTION=" + action + "\n(Via Status Sync)");
                    }
                    payment.setCode(rc);
                    payment.setCallbackProcessed(true);
                    paymentRepository.save(payment);
                    assignSubscriptionIfPossible(payment);
                } else if (action != null && rc != null) {
                    log.info("[ABB][TRTYPE=90] Sync: Payment failed/declined for orderId={}, action={}, rc={}", orderId, action, rc);
                    payment.setStatus("FAILED");
                    payment.setCode(rc);
                    payment.setCallbackProcessed(true);
                    paymentRepository.save(payment);
                } else {
                    java.time.Instant thirtyMinutesAgo = java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.MINUTES);
                    if (payment.getCreatedDate() != null && payment.getCreatedDate().atZone(java.time.ZoneId.systemDefault()).toInstant().isBefore(thirtyMinutesAgo)) {
                        log.info("[ABB][TRTYPE=90] Sync: Payment is older than 30 minutes with no bank status. Marking as FAILED.");
                        payment.setStatus("FAILED");
                        payment.setCallbackProcessed(true);
                        paymentRepository.save(payment);
                    } else {
                        log.warn("[ABB][TRTYPE=90] Sync: Status unresolved for orderId={}", orderId);
                    }
                }
            });

            if ((action != null && !action.isBlank()) || (rc != null && !rc.isBlank())) {
                String masked = paymentOpt.map(p -> maskCardToLast4(p.getCardMask())).orElse(null);
                Payment updatedPayment = paymentRepository.findByOrderId(orderId).orElse(null);
                return buildSuccessResponse(action, rc, approval, rrn, intRef, masked, updatedPayment);
            } else {
                Payment updatedPayment = paymentRepository.findByOrderId(orderId).orElse(null);
                if (updatedPayment != null && "FAILED".equals(updatedPayment.getStatus())) {
                    String masked = maskCardToLast4(updatedPayment.getCardMask());
                    return buildSuccessResponse("3", "96", null, null, null, masked, updatedPayment);
                }
                return AbbTransactionActionResponse.error(
                        String.format("Bank status sorğusu mənfi: action=%s, rc=%s. %s", action, rc, body));
            }

        } catch (Exception e) {
            log.error("[ABB][TRTYPE=90] Status inquiry failed for orderId={}", orderId, e);
            return AbbTransactionActionResponse.error("Status sorğusu uğursuz oldu: " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Redirect URL metodları
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Uğurlu ödənişdən sonra istifadəçinin yönləndiriləcəyi URL-i qaytarır.
     */
    public String getSuccessRedirectUrl() {
        return abbProperties.getSuccessRedirectUrl();
    }

    /**
     * Uğursuz ödənişdən sonra istifadəçinin yönləndiriləcəyi URL-i qaytarır.
     */
    public String getErrorRedirectUrl() {
        return abbProperties.getErrorRedirectUrl();
    }

    /**
     * Uğurlu ödənişdən sonra yönləndiriləcək mütləq local uğur endpoint-i.
     */
    public String getAbsoluteLocalSuccessRedirectUrl() {
        String callback = abbProperties.getCallbackUrl();
        if (callback != null && callback.endsWith("/callback")) {
            return callback.replace("/callback", "/redirect/success");
        }
        return "https://api.fitnest.az/payment/abb/redirect/success";
    }

    /**
     * Uğursuz ödənişdən sonra yönləndiriləcək mütləq local xəta endpoint-i.
     */
    public String getAbsoluteLocalErrorRedirectUrl() {
        String callback = abbProperties.getCallbackUrl();
        if (callback != null && callback.endsWith("/callback")) {
            return callback.replace("/callback", "/redirect/error");
        }
        return "https://api.fitnest.az/payment/abb/redirect/error";
    }


    // ══════════════════════════════════════════════════════════════════════════
    // Xüsusi köməkçi metodlar
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Callback sahələrinin məcburi tələblərini yoxlayır.
     */
    private void validateCallbackFields(AbbCallbackResponse callback) {
        if (callback.getOrder() == null || callback.getOrder().isBlank()) {
            throw new IllegalArgumentException("error.abb_missing_field: ORDER");
        }
        if (callback.getPSign() == null || callback.getPSign().isBlank()) {
            throw new IllegalArgumentException("error.abb_missing_field: P_SIGN");
        }
        if (callback.getTerminal() == null || callback.getTerminal().isBlank()) {
            throw new IllegalArgumentException("error.abb_missing_field: TERMINAL");
        }
        if (callback.getAction() == null) {
            throw new IllegalArgumentException("error.abb_missing_field: ACTION");
        }
        // Uğurlu callback üçün RRN məcburidir
        if (ACTION_SUCCESS.equals(callback.getAction()) && RC_APPROVED.equals(callback.getRc())) {
            if (callback.getRrn() == null || callback.getRrn().isBlank()) {
                throw new IllegalArgumentException("error.abb_missing_field: RRN (required for successful payment)");
            }
            if (callback.getIntRef() == null || callback.getIntRef().isBlank()) {
                throw new IllegalArgumentException("error.abb_missing_field: INT_REF (required for successful payment)");
            }
        }
    }

    /**
     * Callback məlumatlarından Payment entity-ni yeniləyir.
     *
     * <p>{@code transactionId} DB-də UNIQUE constraint-ə malikdir. INT_REF bank
     * tərəfindən unikal verilməlidir, lakin kolliziya halında {@code orderId + INT_REF}
     * composite dəyəri fallback kimi istifadə edilir.</p>
     */
    private void updatePaymentFromCallback(Payment payment, AbbCallbackResponse callback) {
        boolean success = callback.isSuccessful();
        payment.setStatus(success ? "SUCCESS" : "FAILED");

        // RRN → rrn + bankTransaction (mövcud entity field-ləri ilə uyğunluq)
        if (callback.getRrn() != null && !callback.getRrn().isBlank()) {
            payment.setRrn(callback.getRrn());
            payment.setBankTransaction(callback.getRrn());
        }

        // INT_REF → transactionId (UNIQUE constraint var — kolliziya yoxlanılır)
        if (callback.getIntRef() != null && !callback.getIntRef().isBlank()) {
            String intRef = callback.getIntRef();
            boolean intRefAlreadyUsed = paymentRepository.findByTransactionId(intRef)
                    .map(existing -> !existing.getId().equals(payment.getId()))
                    .orElse(false);
            if (!intRefAlreadyUsed) {
                payment.setTransactionId(intRef);
            } else {
                log.warn("[ABB][Callback] INT_REF kolliziyası: intRef={}, orderId={}. " +
                        "Composite transactionId istifadə olunur.", intRef, payment.getOrderId());
                payment.setTransactionId(payment.getOrderId() + "_" + intRef);
            }
        }

        // APPROVAL → bankResponse + operationCode
        if (callback.getApproval() != null && !callback.getApproval().isBlank()) {
            payment.setBankResponse("APPROVAL=" + callback.getApproval()
                    + "\nRC=" + callback.getRc()
                    + "\nACTION=" + callback.getAction());
            payment.setOperationCode(callback.getApproval());
        }

        // Response code → code field-inə
        payment.setCode(callback.getRc());

        // CARD → cardMask (maskalanmış kart məlumatı)
        if (callback.getCard() != null && !callback.getCard().isBlank()) {
            payment.setCardMask(callback.getCard());
        }
    }

    /**
     * Abunəliyi order-backend-ə gRPC ilə təyin etməyə cəhd edir (silent fail on error).
     * ABB historically always passes autoPaymentEnabled=false.
     */
    private void assignSubscriptionIfPossible(Payment payment) {
        paymentSubscriptionService.assignFromPaymentDescription(payment, false);
    }

    /**
     * Ödəniş açıqlaması qurur (packageId/optionId məlumatları daxil).
     */
    private String buildDescription(Long packageId, Long optionId) {
        return PaymentPackageRef.encode(packageId, optionId);
    }

    /**
     * Azericard Gateway URL-ini GET parametrləri ilə qurur.
     *
     * <p>Azericard inteqrasiyasında merchant server parametrləri hazırlayır
     * və istifadəçi brauzerini bu URL-ə yönləndirir. Ödəniş formu bank tərəfindədir.</p>
     */
    private String buildGatewayRedirectUrl(AbbPaymentRequest request) {
        try {
            StringBuilder sb = new StringBuilder(abbProperties.getGatewayUrl());
            sb.append('?');
            appendParam(sb, "AMOUNT",     request.getAmount(),    false);
            appendParam(sb, "CURRENCY",   request.getCurrency(),  true);
            appendParam(sb, "ORDER",      request.getOrder(),     true);
            appendParam(sb, "DESC",       request.getDesc(),      true);
            appendParam(sb, "TRTYPE",     request.getTrtype(),    true);
            appendParam(sb, "TIMESTAMP",  request.getTimestamp(), true);
            appendParam(sb, "NONCE",      request.getNonce(),     true);
            appendParam(sb, "BACKREF",    request.getBackref(),   true);
            appendParam(sb, "P_SIGN",     request.getPSign(),     true);
            appendParam(sb, "TERMINAL",   request.getTerminal(),  true);
            appendParam(sb, "MERCH_NAME", request.getMerchName(), true);
            appendParam(sb, "MERCH_URL",  request.getMerchUrl(),  true);
            appendParam(sb, "EMAIL",      request.getEmail(),     true);
            appendParam(sb, "COUNTRY",    request.getCountry(),   true);
            appendParam(sb, "MERCH_GMT",  request.getMerchGmt(),  true);
            appendParam(sb, "LANG",       request.getLang(),      true);

            // İsteğe bağlı: taksit parametrini əlavə et
            if (request.getAcqInstPayin() != null && !request.getAcqInstPayin().isBlank()) {
                appendParam(sb, "ACQ_INST_PAYIN", request.getAcqInstPayin(), true);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("error.abb_url_build_failed", e);
        }
    }

    /**
     * URL parametrini StringBuilder-ə əlavə edir (URL encoding ilə).
     */
    private void appendParam(StringBuilder sb, String key, String value, boolean ampersand)
            throws java.io.UnsupportedEncodingException {
        if (value == null) return;
        if (ampersand) sb.append('&');
        sb.append(URLEncoder.encode(key, "UTF-8"));
        sb.append('=');
        sb.append(URLEncoder.encode(value, "UTF-8"));
    }

    /**
     * Pending Payment entity-sini yaradır (ödəniş tamamlanmadan əvvəl).
     */
    private Payment createPendingPayment(String orderId, double amount, String currency,
                                         Long userId, String description,
                                         AbbInstallmentOption installment) {
        Payment payment = new Payment();
        payment.setProvider(PROVIDER_ABB);
        payment.setOrderId(orderId);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setStatus("PENDING_USER_ACTION");
        payment.setUserId(userId);
        payment.setDescription(description);
        payment.setType(installment.isInstallment() ? "ABB_INSTALLMENT" : "ABB_PAYMENT");
        payment.setAutoPaymentEnabled(false);
        payment.setCallbackProcessed(false);
        return payment;
    }

    /**
     * Collision-proof unikal orderId yaradır.
     *
     * <p>Format: {@code epochMs + 4 rəqəmli random suffix} (17 simvol).
     * Spec tələbi: 6-32 rəqəm, terminal üzərə gündə unikal.</p>
     */
    private String generateOrderId() {
        long epochMs = Instant.now().toEpochMilli();      // 13 rəqəm
        int randomSuffix = ORDER_RANDOM.nextInt(9000) + 1000; // 1000-9999
        return epochMs + String.valueOf(randomSuffix);    // 17 rəqəm, spec: max 32
    }

    /**
     * Miqdar dəyərini string-ə çevirir (mərc nöqtəsindən sonra 2 rəqəm).
     */
    private String formatAmount(double amount) {
        return String.format("%.2f", amount);
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * TRTYPE=21/22/24 üçün ümumi bank sorğusu köməkçi metodu.
     *
     * <p>Spec §2.1.1-2.2.4 əsasında bu əməliyyatların hamısı eyni MAC sahə sırasına
     * malikdir: {@code AMOUNT → CURRENCY → TERMINAL → TRTYPE → ORDER → RRN → INT_REF}</p>
     *
     * <p>Sorğu bank gateway-inə birbaşa server-to-server HTTP POST ilə göndərilir
     * (istifadəçi brauzeri iştirak etmir).</p>
     *
     * @param trtype      əməliyyat növü: "21", "22", "24"
     * @param request     tələb olunan sahələr
     * @param logContext  log mesajları üçün kontekst sətri
     * @return bank cavabı
     */
    private AbbTransactionActionResponse executeTransactionAction(
            String trtype,
            AbbTransactionActionRequest request,
            String logContext) {

        String amountStr = formatAmount(request.amount());
        String currency  = request.currency() != null ? request.currency() : abbProperties.getDefaultCurrency();
        String timestamp = abbSigner.generateTimestamp();
        String nonce     = abbSigner.generateNonce();

        // Spec §2.2.2-2.2.4: MAC sahə sırası
        // AMOUNT, CURRENCY, TERMINAL, TRTYPE, ORDER, RRN, INT_REF
        String macSource = abbSigner.buildMacSource(
                amountStr,
                currency,
                abbProperties.getTerminalId(),
                trtype,
                request.orderId(),
                request.rrn(),
                request.intRef()
        );
        String pSign = abbSigner.sign(macSource, abbProperties.getPrivateKey());

        try {
            StringBuilder sb = new StringBuilder();
            appendParam(sb, "AMOUNT",    amountStr,                      false);
            appendParam(sb, "CURRENCY",  currency,                       true);
            appendParam(sb, "ORDER",     request.orderId(),              true);
            appendParam(sb, "RRN",       request.rrn(),                  true);
            appendParam(sb, "INT_REF",   request.intRef(),               true);
            appendParam(sb, "TERMINAL",  abbProperties.getTerminalId(),  true);
            appendParam(sb, "TRTYPE",    trtype,                         true);
            appendParam(sb, "TIMESTAMP", timestamp,                      true);
            appendParam(sb, "NONCE",     nonce,                          true);
            appendParam(sb, "P_SIGN",    pSign,                          true);
            String formBody = sb.toString();

            log.info("[ABB][{}] Sending to gateway. orderId={}", logContext, request.orderId());

            java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(abbProperties.getGatewayUrl()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            java.net.http.HttpResponse<String> httpResponse =
                    HTTP_CLIENT.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString());

            log.info("[ABB][{}] Gateway response: httpStatus={}, body={}",
                    logContext, httpResponse.statusCode(), httpResponse.body());

            // Bank cavabını parse et — cavab XML, JSON, və ya HTML ola bilər
            String body = httpResponse.body() != null ? httpResponse.body().trim() : "";
            String action;
            String rc;
            String approval;
            String rrn;
            String intRef;

            if ("0".equals(body)) {
                action = ACTION_SUCCESS;
                rc = RC_APPROVED;
                approval = "";
                rrn = request.rrn();
                intRef = request.intRef();
            } else if ("1".equals(body) || "2".equals(body) || "3".equals(body)) {
                action = body;
                rc = "96";
                approval = "";
                rrn = "";
                intRef = "";
            } else {
                action   = extractTagValue(body, "action");
                rc       = extractTagValue(body, "rc");
                approval = extractTagValue(body, "approval");
                rrn      = extractTagValue(body, "rrn");
                intRef   = extractTagValue(body, "int_ref");
            }

            boolean responseSuccess = ACTION_SUCCESS.equals(action) && RC_APPROVED.equals(rc);

            if (responseSuccess) {
                log.info("[ABB][{}] SUCCESS: order={}, rrn={}, approval={}",
                        logContext, request.orderId(), rrn, approval);
                return AbbTransactionActionResponse.success(action, rc, approval, rrn, intRef);
            } else {
                log.warn("[ABB][{}] DECLINED: order={}, action={}, rc={}",
                        logContext, request.orderId(), action, rc);
                return AbbTransactionActionResponse.error(
                        String.format("Bank cavabı: action=%s, rc=%s. %s", action, rc, body));
            }

        } catch (Exception e) {
            log.error("[ABB][{}] Failed for orderId={}", logContext, request.orderId(), e);
            return AbbTransactionActionResponse.error("error.abb_completion_failed: " + e.getMessage());
        }
    }

    /**
     * Dəstəklənən taksit müddətlərini (ay saylarını) qaytarır.
     */
    public java.util.List<Integer> getSupportedInstallments() {
        return abbProperties.getActiveInstallmentMonths();
    }

    /**
     * XML/HTML body-dən teq dəyərini çıxarır.
     * Azericard cavabları adətən sadə XML teqləri ilə gəlir.
     * Məs: {@code <action>0</action>} → {@code "0"}
     *
     * @param body cavab body mətni
     * @param tag  axtarılacaq teq adı (kiçik hərflə)
     * @return teq dəyəri və ya null
     */
    private String extractTagValue(String body, String tag) {
        if (body == null) return null;
        String lBody = body.toLowerCase();
        String open  = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = lBody.indexOf(open);
        if (start < 0) return null;
        int end = lBody.indexOf(close, start);
        if (end < 0) return null;
        return body.substring(start + open.length(), end).trim();
    }

    private String maskCardToLast4(String cardMask) {
        if (cardMask == null || cardMask.isBlank()) {
            return cardMask;
        }
        String clean = cardMask.replaceAll("\\s+", "");
        if (clean.length() < 4) {
            return clean;
        }
        return "************" + clean.substring(clean.length() - 4);
    }

    private AbbTransactionActionResponse buildSuccessResponse(String action, String rc, String approval,
                                                              String rrn, String intRef, String maskedCard, Payment payment) {
        if (payment == null) {
            String paymentStatus = ("0".equals(action) && "00".equals(rc)) ? "Uğurlu" : "Uğursuz";
            return AbbTransactionActionResponse.builder()
                    .status(paymentStatus)
                    .action(action).rc(rc).approval(approval).rrn(rrn).intRef(intRef).card(maskedCard)
                    .bank("ABB")
                    .build();
        }

        String brand;
        if ("GOOGLE_PAY".equals(payment.getType())) {
            brand = "Google Pay";
        } else if ("APPLE_PAY".equals(payment.getType())) {
            brand = "Apple Pay";
        } else {
            brand = CardBrandDetector.detectBrand(payment.getCardMask());
            if (brand == null || brand.isBlank()) {
                brand = "UNKNOWN";
            }
        }

        String lang = resolveLanguage(payment.getUserId());
        String actualType = az.fitnest.payment.util.PaymentTypeLabels.fromPayment(
                payment.getType(), payment.getAutoPaymentEnabled(), lang);
        log.info("[ABB][Status] paymentId={} typeDb={} typeLocalized={} lang={}",
                payment.getId(), payment.getType(), actualType, lang);

        String ownerName = null;
        if (payment.getUserId() != null) {
            ownerName = userDisplayNameResolver.resolveFullName(payment.getUserId());
        }

        String occurredAtStr = null;
        if (payment.getCreatedDate() != null) {
            occurredAtStr = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(payment.getCreatedDate().atZone(java.time.ZoneId.systemDefault()).toInstant());
        }

        String formattedStatus = translateStatus(payment.getStatus());

        return AbbTransactionActionResponse.builder()
                .status(formattedStatus)
                .action(action)
                .rc(rc)
                .approval(approval)
                .rrn(rrn)
                .intRef(intRef)
                .card(maskedCard)
                .paymentId(payment.getId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .occurredAt(occurredAtStr)
                .cardBrand(brand)
                .bank("ABB")
                .type(actualType)
                .owner(ownerName)
                .description(payment.getDescription())
                .build();
    }

    private PaymentResponse mapToPaymentResponse(Payment payment) {
        if (payment == null) return null;
        String formattedStatus = translateStatus(payment.getStatus());

        String cardBrand;
        if ("GOOGLE_PAY".equals(payment.getType())) {
            cardBrand = "Google Pay";
        } else if ("APPLE_PAY".equals(payment.getType())) {
            cardBrand = "Apple Pay";
        } else {
            cardBrand = CardBrandDetector.detectBrand(payment.getCardMask());
            if (cardBrand == null || cardBrand.isBlank()) {
                cardBrand = "UNKNOWN";
            }
        }
        String bank = "ABB";

        String actualType = az.fitnest.payment.util.PaymentTypeLabels.fromPayment(
                payment.getType(), payment.getAutoPaymentEnabled(), resolveLanguage(payment.getUserId()));

        String ownerName = null;
        if (payment.getUserId() != null) {
            ownerName = userDisplayNameResolver.resolveFullName(payment.getUserId());
        }

        return new PaymentResponse(
            payment.getId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getCreatedDate() != null ? payment.getCreatedDate().atZone(java.time.ZoneId.systemDefault()).toInstant() : null,
            cardBrand,
            bank,
            maskCardToLast4(payment.getCardMask()),
            actualType,
            formattedStatus,
            payment.getCode(),
            payment.getTransactionId(),
            ownerName,
            payment.getDescription(),
            payment.getRrn()
        );
    }

    private String resolveLanguage(Long userId) {
        try {
            if (userId != null) {
                String lang = userGrpcClient.getUserLanguage(userId);
                if (lang != null && !lang.isBlank()) {
                    return az.fitnest.payment.util.PaymentTypeLabels.normalizeLang(lang);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs) {
                String accept = servletAttrs.getRequest().getHeader("Accept-Language");
                if (accept != null && !accept.isBlank()) {
                    return az.fitnest.payment.util.PaymentTypeLabels.normalizeLang(accept.split("[,;-]")[0]);
                }
            }
        } catch (Exception ignored) {
        }
        return "AZ";
    }

    private String translateStatus(String status) {
        if (status == null) return "";
        return switch (status) {
            case "SUCCESS" -> "Uğurlu";
            case "FAILED" -> "Uğursuz";
            case "PENDING", "PENDING_USER_ACTION" -> "Gözləmədə";
            default -> status;
        };
    }
}
