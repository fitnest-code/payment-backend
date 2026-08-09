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
 */
@Slf4j
@RestController
@RequestMapping("/payment/abb/bnpl")
@RequiredArgsConstructor
@Tag(name = "ABB BNPL", description = "ABB Buy Now Pay Later partner integration")
public class BnplPaymentController {

    private final BnplIntegrationService bnplIntegrationService;
    private final AbbBnplProperties properties;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    private static final String MAINTENANCE_MESSAGE =
            "Hazırda ABB BNPL ödəniş sistemində texniki işlər aparılır. Xidmət tezliklə istifadəyə veriləcək";

    private void checkMaintenance(String endpoint) {
        if (properties.isMaintenanceMode()) {
            log.info("[BNPL][Controller] {} blocked — maintenance mode active", endpoint);
            throw new BnplMaintenanceException(MAINTENANCE_MESSAGE);
        }
    }

    @Operation(summary = "BNPL sifarişi yarat (Submit Order)")
    @PostMapping("/init")
    public ResponseEntity<BnplInitResponse> initiate(
            @Valid @RequestBody BnplInitRequest request,
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
            return ResponseEntity.ok(bnplIntegrationService.initiate(userId, request));
        } catch (BnplPaymentException e) {
            return ResponseEntity.badRequest()
                    .body(BnplInitResponse.error(e.getErrorCode(), e.getMessage()));
        }
    }

    @Operation(summary = "Dəstəklənən BNPL kredit müddətləri")
    @GetMapping("/terms")
    public ResponseEntity<List<Integer>> getTerms() {
        checkMaintenance("/terms");
        return ResponseEntity.ok(bnplIntegrationService.getSupportedTerms());
    }

    @Operation(summary = "BNPL status (lokal + lazım olsa ABB-dən fallback poll)")
    @GetMapping("/status/{id}")
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
            description = "ABB status dəyişikliklərini Basic Auth + X-ABB-Callback-Id ilə qəbul edir. "
                    + "5 saniyə ərzində HTTP 200 qaytarmalıdır."
    )
    @PostMapping(value = "/callback", consumes = {"application/json", "*/*"})
    public ResponseEntity<Map<String, String>> handleCallback(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-ABB-Callback-Id", required = false) String callbackId,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody(required = false) String rawBody) {

        BnplCallbackPayload payload = null;
        try {
            if (rawBody != null && !rawBody.isBlank()) {
                payload = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(rawBody, BnplCallbackPayload.class);
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
            bnplIntegrationService.processCallback(payload, callbackId, authorization, signature, rawBody);
            return ResponseEntity.ok(Map.of("status", "OK"));
        } catch (BnplPaymentException e) {
            if ("BNPL_CALLBACK_UNAUTHORIZED".equals(e.getErrorCode())
                    || "BNPL_CALLBACK_SIGNATURE".equals(e.getErrorCode())) {
                log.warn("[BNPL][Callback] Auth/signature failure: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("status", "UNAUTHORIZED", "message", e.getMessage()));
            }
            log.error("[BNPL][Callback] Processing error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "ERROR", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("[BNPL][Callback] Unexpected error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "ERROR"));
        }
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callbackProbe() {
        return ResponseEntity.ok("OK");
    }

    @Operation(summary = "BNPL full reverse")
    @PutMapping("/{id}/reverse")
    public ResponseEntity<Map<String, Object>> fullReverse(@PathVariable String id) {
        checkMaintenance("/reverse");
        bnplIntegrationService.fullReverse(id);
        return ResponseEntity.ok(Map.of("status", "OK", "id", id));
    }

    @Operation(summary = "BNPL partial reverse (optional; MVP may disable on mobile)")
    @PutMapping("/{id}/partial-reverse")
    public ResponseEntity<Map<String, Object>> partialReverse(
            @PathVariable String id,
            @Valid @RequestBody BnplPartialReverseRequest body) {
        checkMaintenance("/partial-reverse");
        bnplIntegrationService.partialReverse(id, body.getAmount());
        return ResponseEntity.ok(Map.of("status", "OK", "id", id, "amount", body.getAmount()));
    }

    private Long extractUserId(Authentication authentication) {
        return authentication != null ? (Long) authentication.getPrincipal() : null;
    }
}
