package az.fitnest.payment.controller;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.abb.bnpl.AbbBnplProperties;
import az.fitnest.payment.dto.abb.bnpl.*;
import az.fitnest.payment.exception.BnplMaintenanceException;
import az.fitnest.payment.exception.BnplPaymentException;
import az.fitnest.payment.service.BnplIntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ABB BNPL (Buy Now Pay Later) endpoints for mobile + bank callback.
 *
 * <p>The callback is exposed at both {@code /payment/abb/bnpl/callback} (direct) and
 * {@code /api/v2/payment/abb/bnpl/callback} (through the gateway, which forwards the path
 * unchanged).</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "ABB BNPL", description = "ABB Buy Now Pay Later partner integration")
public class BnplPaymentController {

    private static final String MAINTENANCE_MESSAGE =
            "Hazırda ABB BNPL ödəniş sistemində texniki işlər aparılır. Xidmət tezliklə istifadəyə veriləcək";

    private final BnplIntegrationService bnplIntegrationService;
    private final AbbBnplProperties properties;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    private void checkMaintenance(String endpoint) {
        if (properties.isMaintenanceMode()) {
            log.info("[BNPL][Controller] {} blocked — maintenance mode active", endpoint);
            throw new BnplMaintenanceException(MAINTENANCE_MESSAGE);
        }
    }

    @Operation(summary = "BNPL sifarişi yarat (v1)", description = "Köhnə axın: Coin endirimi / coinsToUse yoxdur.")
    @PostMapping("/payment/abb/bnpl/init")
    public ResponseEntity<BnplInitResponse> initiate(
            @Valid @RequestBody BnplInitRequest request,
            Authentication authentication) {
        request.setIsCoinUsed(false);
        request.setCoinsToUse(null);
        return initiateInternal(request, authentication);
    }

    @Operation(summary = "BNPL sifarişi yarat (v2)", description = "Yeni axın: isCoinUsed ilə Coin endirimi.")
    @PostMapping("/api/v2/payment/abb/bnpl/init")
    public ResponseEntity<BnplInitResponse> initiateV2(
            @Valid @RequestBody BnplInitRequest request,
            Authentication authentication) {
        return initiateInternal(request, authentication);
    }

    private ResponseEntity<BnplInitResponse> initiateInternal(
            BnplInitRequest request,
            Authentication authentication) {

        checkMaintenance("/init");
        Long userId = extractUserId(authentication);

        boolean packageOk;
        try {
            packageOk = subscriptionPackageGrpcClient.checkOptionInPackageExists(
                    request.getPackageId(), request.getOptionId());
        } catch (Exception e) {
            log.warn("[BNPL][Controller] package check warning: {}", e.getMessage());
            packageOk = request.getPackageId() != null && request.getOptionId() != null;
        }
        if (!packageOk) {
            return ResponseEntity.badRequest()
                    .body(BnplInitResponse.error("INVALID_PACKAGE_OPTION", "Etibarsız packageId və ya optionId"));
        }

        try {
            BnplInitResponse response = bnplIntegrationService.initiate(userId, request);
            HttpStatus status = resolveInitHttpStatus(response);
            return ResponseEntity.status(status).body(response);
        } catch (BnplPaymentException e) {
            return ResponseEntity.status(e.getHttpStatus())
                    .body(BnplInitResponse.error(e.getErrorCode(), e.getMessage(),
                            e.isRetryable(), safeTerms()));
        }
    }

    /**
     * 200 = new order created; 202 = outcome unknown, poll the reference;
     * 409 = an active order already exists and its reference is returned.
     */
    private HttpStatus resolveInitHttpStatus(BnplInitResponse response) {
        if (response == null) {
            return HttpStatus.OK;
        }
        if ("BNPL_ORDER_ALREADY_EXISTS".equals(response.getErrorCode())) {
            return HttpStatus.CONFLICT;
        }
        if ("pending".equalsIgnoreCase(response.getStatus())) {
            return HttpStatus.ACCEPTED;
        }
        return HttpStatus.OK;
    }

    private List<Integer> safeTerms() {
        try {
            return bnplIntegrationService.getSupportedTerms();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Operation(summary = "Dəstəklənən BNPL kredit müddətləri (rəqəm)",
            description = "ABB `term` sahəsini rəqəm kimi gözləyir. Bu siyahı konfiqurasiyadan gəlir.")
    @GetMapping({"/payment/abb/bnpl/terms", "/api/v2/payment/abb/bnpl/terms"})
    public ResponseEntity<List<Integer>> getTerms() {
        checkMaintenance("/terms");
        return ResponseEntity.ok(bnplIntegrationService.getSupportedTerms());
    }

    @Operation(summary = "Dəstəklənən BNPL müddətləri (rəqəm + etiket)",
            description = "Etiket yalnız göstərmək üçündür; ABB-yə həmişə `value` göndərilir.")
    @GetMapping({"/payment/abb/bnpl/terms/detail", "/api/v2/payment/abb/bnpl/terms/detail"})
    public ResponseEntity<List<BnplTermOption>> getTermOptions() {
        checkMaintenance("/terms/detail");
        return ResponseEntity.ok(bnplIntegrationService.getTermOptions());
    }

    @Operation(summary = "BNPL status (lokal + lazım olsa ABB-dən fallback poll)")
    @GetMapping({"/payment/abb/bnpl/status/{id}", "/api/v2/payment/abb/bnpl/status/{id}"})
    public ResponseEntity<BnplStatusResponse> getStatus(
            @PathVariable String id,
            @RequestParam(defaultValue = "true") boolean refresh) {
        checkMaintenance("/status");
        BnplStatusResponse response = refresh
                ? bnplIntegrationService.refreshStatus(id)
                : bnplIntegrationService.getLocalStatus(id);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "ABB BNPL callback webhook",
            description = "ABB status dəyişikliklərini HMAC-SHA256 (X-Signature) və ya Basic Auth ilə göndərir. "
                    + "5 saniyə ərzində HTTP 200 qaytarmalıdır; naməlum sifarişlər üçün də 200 qaytarılır və "
                    + "hadisə reconciliation üçün saxlanılır."
    )
    @PostMapping(value = {"/payment/abb/bnpl/callback", "/api/v2/payment/abb/bnpl/callback"},
            consumes = {"application/json", "*/*"})
    public ResponseEntity<Map<String, String>> handleCallback(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-ABB-Callback-Id", required = false) String callbackId,
            @RequestHeader(value = "X-ABB-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] rawBody) {

        String body = rawBody != null ? new String(rawBody, java.nio.charset.StandardCharsets.UTF_8) : null;

        BnplCallbackPayload payload = null;
        try {
            if (body != null && !body.isBlank()) {
                payload = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(body, BnplCallbackPayload.class);
            }
        } catch (Exception e) {
            log.warn("[BNPL][Callback] Invalid JSON body: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "ERROR", "message", "Invalid JSON"));
        }

        log.info("[BNPL][Callback] Received callbackId={} orderId={} status={}",
                callbackId,
                payload != null ? payload.getOrderId() : null,
                payload != null ? payload.getStatus() : null);

        try {
            bnplIntegrationService.processCallback(
                    payload,
                    new BnplIntegrationService.CallbackHeaders(callbackId, timestamp, signature, authorization),
                    rawBody);
            return ResponseEntity.ok(Map.of("status", "OK"));
        } catch (BnplPaymentException e) {
            if ("BNPL_CALLBACK_UNAUTHORIZED".equals(e.getErrorCode())
                    || "BNPL_CALLBACK_SIGNATURE".equals(e.getErrorCode())) {
                log.warn("[BNPL][Callback] Auth/signature failure: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("status", "UNAUTHORIZED", "message", e.getMessage()));
            }
            log.error("[BNPL][Callback] Processing error: {}", e.getMessage());
            // Do not 400 on transient problems: ABB only retries twice.
            return ResponseEntity.accepted()
                    .body(Map.of("status", "RETRY", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("[BNPL][Callback] Unexpected error", e);
            return ResponseEntity.accepted().body(Map.of("status", "RETRY"));
        }
    }

    @GetMapping({"/payment/abb/bnpl/callback", "/api/v2/payment/abb/bnpl/callback"})
    public ResponseEntity<String> callbackProbe() {
        return ResponseEntity.ok("OK");
    }

    @Operation(summary = "BNPL full reverse",
            description = "Yalnız tamamlanmış və heç bir partial reverse edilməmiş sifariş üçün, "
                    + "sorğu tarixindən 30 gün ərzində.")
    @PutMapping({"/payment/abb/bnpl/{id}/reverse", "/api/v2/payment/abb/bnpl/{id}/reverse"})
    public ResponseEntity<Map<String, Object>> fullReverse(@PathVariable String id) {
        checkMaintenance("/reverse");
        bnplIntegrationService.fullReverse(id);
        BnplStatusResponse status = bnplIntegrationService.getLocalStatus(id);
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "id", id,
                "paymentStatus", status.getPaymentStatus(),
                "reversedAmount", status.getReversedAmount()));
    }

    @Operation(summary = "BNPL partial reverse (məbləğ yığılır)",
            description = "Sifariş COMPLETED qalır; yığılan məbləğ tam məbləğə çatdıqda REVERSED olur.")
    @PutMapping({"/payment/abb/bnpl/{id}/partial-reverse", "/api/v2/payment/abb/bnpl/{id}/partial-reverse"})
    public ResponseEntity<Map<String, Object>> partialReverse(
            @PathVariable String id,
            @Valid @RequestBody BnplPartialReverseRequest body) {
        checkMaintenance("/partial-reverse");
        bnplIntegrationService.partialReverse(id, body.getAmount());
        BnplStatusResponse status = bnplIntegrationService.getLocalStatus(id);
        return ResponseEntity.ok(Map.of(
                "status", "OK",
                "id", id,
                "amount", body.getAmount(),
                "paymentStatus", status.getPaymentStatus(),
                "reversedAmount", status.getReversedAmount(),
                "reversableAmount", status.getReversableAmount()));
    }

    private Long extractUserId(Authentication authentication) {
        return authentication != null ? (Long) authentication.getPrincipal() : null;
    }
}
