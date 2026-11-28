package az.fitnest.payment.controller;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.kapital.KapitalProperties;
import az.fitnest.payment.dto.kapital.KapitalInitiateRequest;
import az.fitnest.payment.dto.kapital.KapitalInitiateResponse;
import az.fitnest.payment.dto.kapital.KapitalRefundRequest;
import az.fitnest.payment.dto.kapital.KapitalRefundResponse;
import az.fitnest.payment.dto.kapital.KapitalStatusResponse;
import az.fitnest.payment.exception.KapitalMaintenanceException;
import az.fitnest.payment.service.KapitalIntegrationService;
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
 * Kapital Bank (HPP) payment endpoints.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Kapital Bank Ödənişləri", description = "Kapital Bank HPP (kart və taksit) inteqrasiyası")
public class KapitalPaymentController {

    private static final String MAINTENANCE_MESSAGE =
            "Hazırda Kapital Bank ödəniş sistemində texniki işlər aparılır. Xidmət tezliklə istifadəyə veriləcək";

    private final KapitalIntegrationService kapitalIntegrationService;
    private final KapitalProperties kapitalProperties;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    private void checkMaintenance(String endpoint) {
        if (kapitalProperties.isMaintenanceMode()) {
            log.info("[KAPITAL][Controller] {} blocked — maintenance mode active", endpoint);
            throw new KapitalMaintenanceException(MAINTENANCE_MESSAGE);
        }
    }

    @Operation(summary = "Kapital Bank ödənişi başlat (v1)", description = "Köhnə axın: Coin endirimi yoxdur.")
    @PostMapping("/payment/kapital/init")
    public ResponseEntity<KapitalInitiateResponse> initiatePayment(
            @RequestBody KapitalInitiateRequest request, Authentication authentication) {
        request.setIsCoinUsed(false);
        return initiatePaymentInternal(request, authentication);
    }

    @Operation(summary = "Kapital Bank ödənişi başlat (v2)", description = "Yeni axın: isCoinUsed ilə Coin endirimi.")
    @PostMapping("/api/v2/payment/kapital/init")
    public ResponseEntity<KapitalInitiateResponse> initiatePaymentV2(
            @RequestBody KapitalInitiateRequest request, Authentication authentication) {
        return initiatePaymentInternal(request, authentication);
    }

    private ResponseEntity<KapitalInitiateResponse> initiatePaymentInternal(
            KapitalInitiateRequest request, Authentication authentication) {
        checkMaintenance("/init");
        Long userId = extractUserId(authentication);

        boolean packageOptionExists;
        try {
            packageOptionExists = subscriptionPackageGrpcClient.checkOptionInPackageExists(
                    request.getPackageId(), request.getOptionId());
        } catch (Exception e) {
            log.warn("[KAPITAL][Controller] gRPC checkOptionInPackageExists warning: {}", e.getMessage());
            packageOptionExists = request.getPackageId() != null && request.getOptionId() != null;
        }
        if (!packageOptionExists) {
            return ResponseEntity.badRequest().body(KapitalInitiateResponse.builder()
                    .errorCode("INVALID_PACKAGE_OPTION")
                    .errorMessage("Etibarsız packageId və ya optionId")
                    .build());
        }
        return ResponseEntity.ok(kapitalIntegrationService.initiatePayment(userId, request));
    }

    /**
     * Payer return URL (hppRedirectUrl). ID is the bank order id; STATUS is deliberately ignored —
     * finalization always re-reads GET /order. Public (no JWT): the browser arrives from the bank.
     */
    @Operation(summary = "Kapital Bank nəticə / return URL",
            description = "Bank istifadəçini ID parametri ilə bura yönləndirir; status GET /order ilə yoxlanılır.")
    @RequestMapping(value = {"/payment/kapital/result", "/api/v1/payments/kapital/result"},
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Void> handleResult(
            @RequestParam(value = "ID", required = false) String idUpper,
            @RequestParam(value = "id", required = false) String idLower,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String bankOrderId = idUpper != null && !idUpper.isBlank() ? idUpper : idLower;
        log.info("[KAPITAL][Controller] Result received method={} providerOrderId={}",
                httpRequest.getMethod(), bankOrderId);

        String redirectUrl = kapitalIntegrationService.processResult(bankOrderId);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
    }

    @Operation(
            summary = "Kapital taksit ayları (paket müddətinə görə)",
            description = "Paket option durationMonths göndərilir; cavabda Kapital-ın aktiv taksit "
                    + "aylarından (hazırda 3,6,12) yalnız paket müddətindən böyük olmayanlar qalır. "
                    + "Məs: durationMonths=6 → [3,6]; durationMonths=12 → [3,6,12]."
    )
    @GetMapping({"/payment/kapital/installment", "/api/v2/payment/kapital/installment"})
    public ResponseEntity<java.util.List<Integer>> getEligibleInstallments(
            @RequestParam("durationMonths") int durationMonths) {
        checkMaintenance("/installment GET");
        return ResponseEntity.ok(kapitalIntegrationService.getEligibleInstallments(durationMonths));
    }

    @Operation(summary = "Ödəniş statusunu yoxla", description = "Kapital order ID üzrə status (GET /order ilə sinxron).")
    @GetMapping("/payment/kapital/status/{providerOrderId}")
    public ResponseEntity<KapitalStatusResponse> getStatus(@PathVariable String providerOrderId) {
        checkMaintenance("/status");
        return ResponseEntity.ok(kapitalIntegrationService.checkPaymentStatus(providerOrderId));
    }

    @Operation(summary = "Ödənişi geri qaytar / ləğv et",
            description = "Eyni gün (Bakı) ləğv = void, sonra = refund.")
    @PostMapping("/payment/kapital/refund")
    public ResponseEntity<KapitalRefundResponse> refundPayment(@RequestBody KapitalRefundRequest request) {
        checkMaintenance("/refund");
        return ResponseEntity.ok(kapitalIntegrationService.refundPayment(request));
    }

    private Long extractUserId(Authentication authentication) {
        Long userId = az.fitnest.payment.util.UserContext.getCurrentUserId();
        if (userId != null) {
            return userId;
        }
        if (authentication != null && authentication.getPrincipal() != null) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof Long id) {
                return id;
            }
            if (principal instanceof String s) {
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        // Same fallback as BOB; the security filter rejects unauthenticated init before this in practice.
        return 1L;
    }
}
