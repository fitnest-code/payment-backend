package az.fitnest.payment.controller;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.dto.abb.AbbCallbackResponse;
import az.fitnest.payment.dto.abb.AbbInitiateResponse;
import az.fitnest.payment.dto.abb.AbbInstallmentInitRequest;
import az.fitnest.payment.dto.abb.AbbInstallmentOption;
import az.fitnest.payment.dto.abb.AbbTransactionActionRequest;
import az.fitnest.payment.dto.abb.AbbTransactionActionResponse;
import az.fitnest.payment.service.AbbIntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * ABB (Azericard) E-Commerce Gateway üçün REST controller.
 *
 * <h2>Endpoint-lər</h2>
 * <ul>
 *   <li>{@code POST /payment/abb/init}         — Ödəniş başlatma (taksitsiz)</li>
 *   <li>{@code POST /payment/abb/installment}  — Taksit ödənişi başlatma</li>
 *   <li>{@code POST /payment/abb/callback}     — Azericard BACKREF callback</li>
 *   <li>{@code GET  /payment/abb/redirect/success} — Uğurlu ödəniş yönləndirmə</li>
 *   <li>{@code GET  /payment/abb/redirect/error}   — Uğursuz ödəniş yönləndirmə</li>
 * </ul>
 *
 * <h2>Təhlükəsizlik</h2>
 * <p>Callback endpoint-i JWT authentication-dan muaf tutulur (SecurityConfig-da permitAll).
 * Bunun əvəzinə ABB-nin P_SIGN imzası {@link AbbIntegrationService#processCallback} daxilində
 * RSA ilə doğrulanır.</p>
 */
@Slf4j
@RestController
@RequestMapping("/payment/abb")
@RequiredArgsConstructor
@Tag(name = "ABB Taksit Ödənişləri", description = "ABB (Azericard) E-Commerce Gateway inteqrasiyası")
public class AbbPaymentController {

    private final AbbIntegrationService abbIntegrationService;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    // ══════════════════════════════════════════════════════════════════════════
    // Ödəniş başlatma endpoint-ləri
    // ══════════════════════════════════════════════════════════════════════════

    @Operation(
            summary = "ABB ödənişi başlat (taksitsiz)",
            description = "Azericard ödəniş səhifəsinə yönləndirilmə URL-i qaytarır."
    )
    @PostMapping("/init")
    public ResponseEntity<AbbInitiateResponse> initiatePayment(
            @RequestBody AbbInstallmentInitRequest request,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        log.info("[ABB][Controller] /init called: userId={}, packageId={}, optionId={}",
                userId, request.packageId(), request.optionId());

        if (!validatePackageOption(request.packageId(), request.optionId())) {
            return ResponseEntity.badRequest()
                    .body(AbbInitiateResponse.error("Etibarsız packageId və ya optionId"));
        }

        try {
            AbbInitiateResponse response = abbIntegrationService.initiatePayment(
                    userId, request.packageId(), request.optionId());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[ABB][Controller] /init error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AbbInitiateResponse.error("Daxili server xətası"));
        }
    }

    @Operation(
            summary = "ABB taksit ödənişi başlat",
            description = """
                    Müəyyən taksit sayı ilə Azericard ödəniş səhifəsinə yönləndirilmə URL-i qaytarır.

                    installmentMonths dəyərləri:
                    - Test terminal aktiv dövrlər: 2, 3, 6, 9, 12
                    - Production terminal: bank konfiqurasiyasına əsasən
                    - null və ya 0: taksitsiz ödəniş (ACQ_INST_PAYIN=X)

                    Xəta kodları (taksit konfiqurasiya səhvləri):
                    U1=boş konfiqurasiya, U3=kart BIN tapılmadı, U4=BIN üçün taksit yoxdur,
                    U5=terminal üçün taksit yoxdur, U6=terminal üçün həmin dövr yoxdur,
                    U7=kart BIN üçün həmin dövr yoxdur.

                    Taksit kartı: 4127214121630724 (test).
                    """
    )
    @PostMapping("/installment")
    public ResponseEntity<AbbInitiateResponse> initiateInstallmentPayment(
            @RequestBody AbbInstallmentInitRequest request,
            Authentication authentication) {

        Long userId = extractUserId(authentication);
        log.info("[ABB][Controller] /installment called: userId={}, packageId={}, optionId={}, months={}",
                userId, request.packageId(), request.optionId(), request.installmentMonths());

        if (!validatePackageOption(request.packageId(), request.optionId())) {
            return ResponseEntity.badRequest()
                    .body(AbbInitiateResponse.error("Etibarsız packageId və ya optionId"));
        }

        AbbInstallmentOption installment = resolveInstallmentOption(request.installmentMonths());

        try {
            AbbInitiateResponse response = abbIntegrationService.initiateInstallmentPayment(
                    userId, request.packageId(), request.optionId(), installment);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[ABB][Controller] /installment error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AbbInitiateResponse.error("Daxili server xətası"));
        }
    }

    @Operation(
            summary = "Dəstəklənən ABB taksit ayları",
            description = "Sistemdə aktiv olan və bank tərəfindən dəstəklənən taksit aylarının siyahısını qaytarır."
    )
    @GetMapping("/installments")
    public ResponseEntity<java.util.List<Integer>> getSupportedInstallments() {
        log.info("[ABB][Controller] /installments called");
        return ResponseEntity.ok(abbIntegrationService.getSupportedInstallments());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Callback endpoint
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Azericard-dan gələn BACKREF callback-ini qəbul edir.
     *
     * <p>Bank bu endpoint-ə {@code application/x-www-form-urlencoded} formatında
     * POST sorğusu göndərir. Bütün sahələr individual {@code @RequestParam} kimi
     * alınır (Jackson JSON parse tələb olunmur).</p>
     *
     * <p>Əgər callback imzası etibarlıdırsa, bank {@code OK} cavabı gözləyir.
     * Xəta halında bank callback-i yenidən cəhd edə bilər.</p>
     */
    @Operation(
            summary = "ABB callback",
            description = "Azericard E-Commerce Gateway-dən BACKREF POST callback-i. " +
                    "Bu endpoint JWT autentifikasiyasından muafdır."
    )
    @PostMapping(value = "/callback", consumes = {"application/x-www-form-urlencoded", "*/*"})
    public ResponseEntity<Void> handleCallback(
            @RequestParam(value = "TERMINAL",  required = false) String terminal,
            @RequestParam(value = "TRTYPE",    required = false) String trtype,
            @RequestParam(value = "ORDER",     required = false) String order,
            @RequestParam(value = "AMOUNT",    required = false) String amount,
            @RequestParam(value = "CURRENCY",  required = false) String currency,
            @RequestParam(value = "ACTION",    required = false) String action,
            @RequestParam(value = "RC",        required = false) String rc,
            @RequestParam(value = "APPROVAL",  required = false) String approval,
            @RequestParam(value = "RRN",       required = false) String rrn,
            @RequestParam(value = "INT_REF",   required = false) String intRef,
            @RequestParam(value = "TIMESTAMP", required = false) String timestamp,
            @RequestParam(value = "NONCE",     required = false) String nonce,
            @RequestParam(value = "P_SIGN",    required = false) String pSign,
            @RequestParam(value = "EXT_NET_REF", required = false) String extNetRef,
            @RequestParam(value = "TOKEN",     required = false) String token) {

        log.info("[ABB][Callback] Received: order={}, action={}, rc={}, terminal={}",
                order, action, rc, terminal);

        AbbCallbackResponse callback = AbbCallbackResponse.builder()
                .terminal(terminal)
                .trtype(trtype)
                .order(order)
                .amount(amount)
                .currency(currency)
                .action(action)
                .rc(rc)
                .approval(approval)
                .rrn(rrn)
                .intRef(intRef)
                .timestamp(timestamp)
                .nonce(nonce)
                .pSign(pSign)
                .extNetRef(extNetRef)
                .token(token)
                .build();

        try {
            abbIntegrationService.processCallback(callback);
            log.info("[ABB][Callback] Successfully processed for order={}", order);

            String targetUrl = callback.isSuccessful()
                    ? abbIntegrationService.getAbsoluteLocalSuccessRedirectUrl()
                    : abbIntegrationService.getAbsoluteLocalErrorRedirectUrl();

            log.info("[ABB][Callback] Redirecting (303 SEE_OTHER) to absolute local URL: {}", targetUrl);
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(targetUrl))
                    .build();

        } catch (IllegalArgumentException e) {
            log.warn("[ABB][Callback] Invalid callback data for order={}: {}", order, e.getMessage());
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(abbIntegrationService.getAbsoluteLocalErrorRedirectUrl()))
                    .build();

        } catch (SecurityException e) {
            log.error("[ABB][Callback] Signature verification failed for order={}", order);
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(abbIntegrationService.getAbsoluteLocalErrorRedirectUrl()))
                    .build();

        } catch (Exception e) {
            log.error("[ABB][Callback] Unexpected error for order={}", order, e);
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(abbIntegrationService.getAbsoluteLocalErrorRedirectUrl()))
                    .build();
        }
    }

    /**
     * Azericard-ın GET probe-ı üçün (bank bəzən callback URL-ini GET ilə yoxlayır).
     */
    @GetMapping("/callback")
    public ResponseEntity<String> handleCallbackGet() {
        log.debug("[ABB][Callback] GET probe received — returning OK");
        return ResponseEntity.ok("OK");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Redirect endpoint-ləri
    // ══════════════════════════════════════════════════════════════════════════

    @Operation(
            summary = "ABB uğurlu ödəniş yönləndirmə",
            description = "Azericard ödənişi tamamladıqdan sonra istifadəçini uğur səhifəsinə yönləndirir."
    )
    @GetMapping("/redirect/success")
    public ResponseEntity<Void> redirectSuccess() {
        String targetUrl = abbIntegrationService.getSuccessRedirectUrl();
        log.info("[ABB][Redirect] Success redirect to: {}", targetUrl);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(targetUrl))
                .build();
    }

    @Operation(
            summary = "ABB uğursuz ödəniş yönləndirmə",
            description = "Azericard ödənişi rədd etdikdən sonra istifadəçini xəta səhifəsinə yönləndirir."
    )
    @GetMapping("/redirect/error")
    public ResponseEntity<Void> redirectError() {
        String targetUrl = abbIntegrationService.getErrorRedirectUrl();
        log.info("[ABB][Redirect] Error redirect to: {}", targetUrl);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(targetUrl))
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TRTYPE=21/22/24 — Completion & Reversal endpoint-ləri (admin/internal)
    // ══════════════════════════════════════════════════════════════════════════

    @Operation(
            summary = "ABB ödənişi tamamla (TRTYPE=21)",
            description = "Pre-auth (TRTYPE=0) ilə başladılmış ödənişi tamamlayır. " +
                    "Callback-dən alınmış RRN və INT_REF tələb olunur."
    )
    @PostMapping("/action/complete")
    public ResponseEntity<AbbTransactionActionResponse> completePayment(
            @RequestBody AbbTransactionActionRequest request) {
        log.info("[ABB][Controller] /action/complete called: orderId={}", request.orderId());
        try {
            return ResponseEntity.ok(abbIntegrationService.completePayment(request));
        } catch (Exception e) {
            log.error("[ABB][Controller] /action/complete error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AbbTransactionActionResponse.error("Daxili server xətası"));
        }
    }

    @Operation(
            summary = "ABB online reversal (TRTYPE=22)",
            description = "Eyni bank günündə əməliyyatı geri qaytarır. " +
                    "Callback-dən alınmış RRN, INT_REF tələb olunur."
    )
    @PostMapping("/action/reverse-online")
    public ResponseEntity<AbbTransactionActionResponse> onlineReversal(
            @RequestBody AbbTransactionActionRequest request) {
        log.info("[ABB][Controller] /action/reverse-online called: orderId={}", request.orderId());
        try {
            return ResponseEntity.ok(abbIntegrationService.onlineReversal(request));
        } catch (Exception e) {
            log.error("[ABB][Controller] /action/reverse-online error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AbbTransactionActionResponse.error("Daxili server xətası"));
        }
    }

    @Operation(
            summary = "ABB offline reversal / refund (TRTYPE=24)",
            description = "Bank günü keçdikdən sonra əməliyyatı geri qaytarır (refund). " +
                    "Callback-dən alınmış RRN, INT_REF tələb olunur."
    )
    @PostMapping("/action/reverse-offline")
    public ResponseEntity<AbbTransactionActionResponse> offlineReversal(
            @RequestBody AbbTransactionActionRequest request) {
        log.info("[ABB][Controller] /action/reverse-offline called: orderId={}", request.orderId());
        try {
            return ResponseEntity.ok(abbIntegrationService.offlineReversal(request));
        } catch (Exception e) {
            log.error("[ABB][Controller] /action/reverse-offline error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AbbTransactionActionResponse.error("Daxili server xətası"));
        }
    }

    @Operation(
            summary = "ABB tranzaksiya status sorğusu (TRTYPE=90)",
            description = "Bankdan müəyyən əməliyyatın mövcud statusunu soruşur. " +
                    "orderId: orijinal ORDER, originalTrtype: orijinal əməliyyatın TRTYPE-ı (məs: '1')."
    )
    @GetMapping("/action/status/{orderId}")
    public ResponseEntity<AbbTransactionActionResponse> getTransactionStatus(
            @PathVariable String orderId,
            @RequestParam(defaultValue = "1") String originalTrtype) {
        log.info("[ABB][Controller] /action/status called: orderId={}, originalTrtype={}",
                orderId, originalTrtype);
        try {
            return ResponseEntity.ok(abbIntegrationService.getTransactionStatus(orderId, originalTrtype));
        } catch (Exception e) {
            log.error("[ABB][Controller] /action/status error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AbbTransactionActionResponse.error("Status sorğusu uğursuz oldu"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Köməkçi metodlar
    // ══════════════════════════════════════════════════════════════════════════

    private Long extractUserId(Authentication authentication) {
        return authentication != null ? (Long) authentication.getPrincipal() : null;
    }

    private boolean validatePackageOption(Long packageId, Long optionId) {
        if (packageId == null || optionId == null) {
            return false;
        }
        return subscriptionPackageGrpcClient.checkOptionInPackageExists(packageId, optionId);
    }

    private AbbInstallmentOption resolveInstallmentOption(Integer months) {
        if (months == null || months <= 0) {
            return AbbInstallmentOption.NONE;
        }
        AbbInstallmentOption option = AbbInstallmentOption.fromMonths(months);
        if (option == AbbInstallmentOption.NONE) {
            log.warn("[ABB][Controller] Unrecognized installmentMonths={}, falling back to NONE (ACQ_INST_PAYIN=X)", months);
        }
        return option;
    }
}
