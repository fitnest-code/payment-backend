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
@RequestMapping("/api/v1/payments/apple-pay")
@RequiredArgsConstructor
@Tag(name = "Apple Pay Ödənişləri", description = "Apple Pay inteqrasiyası üçün ucluqlar")
@SecurityRequirement(name = "bearerAuth")
public class ApplePayController {

    private static final Logger log = LoggerFactory.getLogger(ApplePayController.class);

    private final EpointIntegrationService integrationService;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    @Operation(summary = "Apple Pay ödənişini başladın", description = "Apple Pay üçün payment token yaradır.")
    @PostMapping("/create")
    public ResponseEntity<?> createPayment(
            @Valid @RequestBody ApplePayCreateRequest request,
            @AuthenticationPrincipal Principal user) {
        Long userId = UserContext.getCurrentUserId();
        log.info("[ApplePayCreate] (CONTROLLER ENTRY) userId={}, packageId={}, optionId={}", userId, request.packageId(), request.optionId());

        if (userId == null) {
            log.warn("[ApplePayCreate] Unauthorized: User ID not found");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Validate package & option exists
        boolean valid = subscriptionPackageGrpcClient.checkOptionInPackageExists(request.packageId(), request.optionId());
        if (!valid) {
            log.warn("[ApplePayCreate] Invalid packageId or optionId. packageId={}, optionId={}", request.packageId(), request.optionId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Invalid packageId or optionId"));
        }

        try {
            EpointTokenResponse tokenResponse = integrationService.createApplePayPayment(userId, request.packageId(), request.optionId());
            ApplePayCreateResponse createResponse = new ApplePayCreateResponse(tokenResponse.getPaymentId());
            log.info("[ApplePayCreate] (CONTROLLER EXIT) Created paymentId={} for userId={}", createResponse.paymentId(), userId);
            return ResponseEntity.ok(createResponse);
        } catch (Exception e) {
            log.error("[ApplePayCreate] Error creating Apple Pay payment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Internal server error: " + e.getMessage()));
        }
    }

    @Operation(summary = "Apple Pay ödənişini tamamlamaq", description = "Apple Pay-dən gələn token-i Epoint-ə göndərərək ödənişi təsdiqləyir.")
    @PostMapping("/submit")
    public ResponseEntity<?> submitPayment(
            @Valid @RequestBody ApplePaySubmitRequest request,
            @AuthenticationPrincipal Principal user) {
        Long userId = UserContext.getCurrentUserId();
        log.info("[ApplePaySubmit] (CONTROLLER ENTRY) userId={}, paymentId={}", userId, request.paymentId());

        if (userId == null) {
            log.warn("[ApplePaySubmit] Unauthorized: User ID not found");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            ApplePaySubmitResponse submitResponse = integrationService.submitApplePayPayment(userId, request);
            log.info("[ApplePaySubmit] (CONTROLLER EXIT) submit result: status={}, redirectUrl={}", submitResponse.status(), submitResponse.redirectUrl());
            return ResponseEntity.ok(submitResponse);
        } catch (IllegalArgumentException e) {
            log.warn("[ApplePaySubmit] Bad request: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        } catch (SecurityException e) {
            log.warn("[ApplePaySubmit] Security exception: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Unauthorized"));
        } catch (Exception e) {
            log.error("[ApplePaySubmit] Error submitting Apple Pay payment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Internal server error: " + e.getMessage()));
        }
    }

    private record ErrorResponse(String message) {}
}
