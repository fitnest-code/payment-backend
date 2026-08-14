package az.fitnest.payment.controller;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.client.bob.BobProperties;
import az.fitnest.payment.dto.bob.*;
import az.fitnest.payment.exception.BobMaintenanceException;
import az.fitnest.payment.model.entity.UserCard;
import az.fitnest.payment.service.BobIntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * Bank of Baku (SmartVista EPG) Ödənişləri üçün REST controller.
 */
@Slf4j
@RestController
@RequestMapping("/payment/bob")
@RequiredArgsConstructor
@Tag(name = "Bank of Baku Ödənişləri", description = "Bank of Baku (SmartVista EPG) Ödəniş və Kart Saxlama İnteqrasiyası")
public class BobPaymentController {

    private final BobIntegrationService bobIntegrationService;
    private final BobProperties bobProperties;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    private static final String MAINTENANCE_MESSAGE =
            "Hazırda Bank of Baku ödəniş sistemində texniki işlər aparılır. Xidmət tezliklə istifadəyə veriləcək";

    private void checkMaintenance(String endpoint) {
        if (bobProperties.isMaintenanceMode()) {
            log.info("[BOB][Controller] {} blocked — maintenance mode active", endpoint);
            throw new BobMaintenanceException(MAINTENANCE_MESSAGE);
        }
    }

    @Operation(
            summary = "Bank of Baku ödənişi başlat (Single-Phase)",
            description = "SmartVista ödəniş səhifəsinə yönləndirilmə URL-i və Order ID qaytarır."
    )
    @PostMapping("/init")
    public ResponseEntity<BobInitiateResponse> initiatePayment(
            @RequestBody BobInitiateRequest request,
            Authentication authentication) {

        checkMaintenance("/init");
        Long userId = extractUserId(authentication);

        boolean packageOptionExists = false;
        try {
            packageOptionExists = subscriptionPackageGrpcClient.checkOptionInPackageExists(request.getPackageId(), request.getOptionId());
        } catch (Exception e) {
            log.warn("[BOB][Controller] gRPC checkOptionInPackageExists warning: {}", e.getMessage());
            packageOptionExists = request.getPackageId() != null && request.getOptionId() != null;
        }

        if (!packageOptionExists) {
            return ResponseEntity.badRequest()
                    .body(BobInitiateResponse.builder()
                            .errorCode("INVALID_PACKAGE_OPTION")
                            .errorMessage("Etibarsız packageId və ya optionId")
                            .build());
        }

        BobInitiateResponse response = bobIntegrationService.initiatePayment(userId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Saxlanılmış kartla (Binding) ödəniş et",
            description = "İstifadəçinin daha öncə yadda saxladığı kart vasitəsilə 1-Click ödəniş icra edir."
    )
    @PostMapping("/pay-with-card")
    public ResponseEntity<BobInitiateResponse> payWithSavedCard(
            @RequestBody BobPayWithSavedCardRequest request,
            Authentication authentication) {

        checkMaintenance("/pay-with-card");
        Long userId = extractUserId(authentication);

        BobInitiateResponse response = bobIntegrationService.payWithSavedCard(userId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Bank of Baku Callback / Return URL",
            description = "Bankın ödəniş nəticəsini göndərdiyi və ya istifadəçini yönləndirdiyi callback endpoint-i."
    )
    @RequestMapping(value = "/callback", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Void> handleCallback(
            @RequestParam(value = "orderNumber", required = false) String orderNumber,
            @RequestParam(value = "orderId", required = false) String orderId,
            jakarta.servlet.http.HttpServletRequest httpRequest) {

        log.warn("[BOB][Controller] Callback received method={} query={} orderNumber={} orderId={} params={}",
                httpRequest.getMethod(),
                httpRequest.getQueryString(),
                orderNumber,
                orderId,
                httpRequest.getParameterMap());
        String redirectUrl = bobIntegrationService.processCallback(orderNumber, orderId);
        log.warn("[BOB][Controller] Callback redirect orderNumber={} orderId={} redirectUrl={}",
                orderNumber, orderId, redirectUrl);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectUrl))
                .build();
    }

    @Operation(
            summary = "Ödəniş statusunu yoxla",
            description = "SmartVista EPG-dən orderId üzrə son status məlumatını alır."
    )
    @GetMapping("/status/{orderId}")
    public ResponseEntity<BobOrderStatusResponse> getOrderStatus(@PathVariable String orderId) {
        checkMaintenance("/status");
        BobOrderStatusResponse response = bobIntegrationService.checkPaymentStatus(orderId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Dəstəklənən taksit aylarının siyahısı",
            description = "Bank of Baku üçün aktiv taksit aylarının (məs: [2, 3, 6, 9, 12]) siyahısını qaytarır."
    )
    @GetMapping("/installment")
    public ResponseEntity<List<Integer>> getSupportedInstallments() {
        checkMaintenance("/installment GET");
        List<Integer> installments = bobIntegrationService.getSupportedInstallments();
        return ResponseEntity.ok(installments);
    }

    @Operation(
            summary = "İstifadəçinin saxlanılmış kartlarının siyahısı",
            description = "Daxil olmuş istifadəçinin Bank of Baku sistemində yadda saxlanılmış kartlarını qaytarır."
    )
    @GetMapping("/cards")
    public ResponseEntity<List<UserCard>> getUserSavedCards(Authentication authentication) {
        checkMaintenance("/cards");
        Long userId = extractUserId(authentication);
        List<UserCard> cards = bobIntegrationService.getUserSavedCards(userId);
        return ResponseEntity.ok(cards);
    }

    @Operation(
            summary = "Saxlanılmış kartı sil (Unbind)",
            description = "İstifadəçinin seçilmiş kartını silir və bank sistemində deaktiv edir."
    )
    @DeleteMapping("/cards/{cardId}")
    public ResponseEntity<Void> deleteSavedCard(
            @PathVariable String cardId,
            Authentication authentication) {

        checkMaintenance("/cards DELETE");
        Long userId = extractUserId(authentication);
        bobIntegrationService.deleteSavedCard(userId, cardId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Ödənişi geri qaytar (Refund)",
            description = "Tranzaksiya məbləğini qismən və ya tam olaraq geri qaytarır."
    )
    @PostMapping("/refund")
    public ResponseEntity<BobRefundResponse> refundPayment(@RequestBody BobRefundRequest request) {
        checkMaintenance("/refund");
        BobRefundResponse response = bobIntegrationService.refundPayment(request);
        return ResponseEntity.ok(response);
    }

    private Long extractUserId(Authentication authentication) {
        Long userId = az.fitnest.payment.util.UserContext.getCurrentUserId();
        if (userId != null) {
            return userId;
        }
        if (authentication != null && authentication.getPrincipal() != null) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof Long) {
                return (Long) principal;
            }
            if (principal instanceof String) {
                try {
                    return Long.parseLong((String) principal);
                } catch (NumberFormatException ignored) {}
            }
        }
        return 1L;
    }
}
