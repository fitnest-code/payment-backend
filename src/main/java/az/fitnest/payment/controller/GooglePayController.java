package az.fitnest.payment.controller;

import az.fitnest.payment.dto.common.*;
import az.fitnest.payment.dto.epoint.EpointTokenResponse;
import az.fitnest.payment.service.EpointIntegrationService;
import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequiredArgsConstructor
@Tag(name = "Google Pay Ödənişləri", description = "Google Pay inteqrasiyası üçün ucluqlar")
@SecurityRequirement(name = "bearerAuth")
public class GooglePayController {

    private static final Logger log = LoggerFactory.getLogger(GooglePayController.class);

    private final EpointIntegrationService integrationService;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    @Operation(summary = "Google Pay ödənişini başladın (v1)", description = "Köhnə axın / köhnə DTO: Coin endirimi yoxdur.")
    @PostMapping("/api/v1/payments/google-pay/create")
    public ResponseEntity<?> createPayment(
            @Valid @RequestBody GooglePayCreateRequest request,
            @AuthenticationPrincipal Principal user) {
        return createPaymentInternal(request.packageId(), request.optionId(), false);
    }

    @Operation(summary = "Google Pay ödənişini başladın (v2)", description = "Yeni axın / GooglePayCreateRequestV2: isCoinUsed ilə Coin endirimi.")
    @PostMapping("/api/v2/payment/google-pay/create")
    public ResponseEntity<?> createPaymentV2(
            @Valid @RequestBody GooglePayCreateRequestV2 request,
            @AuthenticationPrincipal Principal user) {
        return createPaymentInternal(request.packageId(), request.optionId(), request.isCoinUsed());
    }

    private ResponseEntity<?> createPaymentInternal(Long packageId, Long optionId, Boolean isCoinUsed) {
        Long userId = UserContext.getCurrentUserId();
        log.info("[GooglePayCreate] (CONTROLLER ENTRY) userId={}, packageId={}, optionId={}, isCoinUsed={}",
                userId, packageId, optionId, isCoinUsed);

        if (userId == null) {
            log.warn("[GooglePayCreate] Unauthorized: User ID not found");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        boolean valid = subscriptionPackageGrpcClient.checkOptionInPackageExists(packageId, optionId);
        if (!valid) {
            log.warn("[GooglePayCreate] Invalid packageId or optionId. packageId={}, optionId={}", packageId, optionId);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Invalid packageId or optionId"));
        }

        try {
            EpointTokenResponse tokenResponse = integrationService.createGooglePayPayment(
                    userId, packageId, optionId, isCoinUsed);
            GooglePayCreateResponse createResponse = new GooglePayCreateResponse(tokenResponse.getPaymentId());
            log.info("[GooglePayCreate] (CONTROLLER EXIT) Created paymentId={} for userId={}", createResponse.paymentId(), userId);
            return ResponseEntity.ok(createResponse);
        } catch (Exception e) {
            log.error("[GooglePayCreate] Error creating Google Pay payment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Internal server error: " + e.getMessage()));
        }
    }

    @Operation(summary = "Google Pay ödənişini tamamlamaq", description = "Google Pay-dən gələn token-i Epoint-ə göndərərək ödənişi təsdiqləyir.")
    @PostMapping({"/api/v1/payments/google-pay/submit", "/api/v2/payment/google-pay/submit"})
    public ResponseEntity<?> submitPayment(
            @Valid @RequestBody GooglePaySubmitRequest request,
            @AuthenticationPrincipal Principal user) {
        Long userId = UserContext.getCurrentUserId();
        log.info("[GooglePaySubmit] (CONTROLLER ENTRY) userId={}, paymentId={}", userId, request.paymentId());

        if (userId == null) {
            log.warn("[GooglePaySubmit] Unauthorized: User ID not found");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            GooglePaySubmitResponse submitResponse = integrationService.submitGooglePayPayment(userId, request);
            log.info("[GooglePaySubmit] (CONTROLLER EXIT) submit result: status={}, redirectUrl={}", submitResponse.status(), submitResponse.redirectUrl());
            return ResponseEntity.ok(submitResponse);
        } catch (IllegalArgumentException e) {
            log.warn("[GooglePaySubmit] Bad request: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        } catch (SecurityException e) {
            log.warn("[GooglePaySubmit] Security exception: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Unauthorized"));
        } catch (Exception e) {
            log.error("[GooglePaySubmit] Error submitting Google Pay payment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Internal server error: " + e.getMessage()));
        }
    }

    private record ErrorResponse(String message) {}
}
