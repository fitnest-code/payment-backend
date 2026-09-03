package az.fitnest.payment.controller;

import az.fitnest.payment.service.EpointIntegrationService;
import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.dto.common.ApiResponse;
import az.fitnest.payment.dto.common.ApiError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import az.fitnest.payment.dto.epoint.*;
import az.fitnest.payment.util.PaymentPackageRef;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.redis.core.StringRedisTemplate;

@RestController
@RequiredArgsConstructor
@Tag(name = "Ödənişlər", description = "Ödəniş inteqrasiyası üçün ucluqlar")
public class PaymentController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentController.class);

    private final EpointIntegrationService integrationService;
    private final StringRedisTemplate redisTemplate;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    @Operation(summary = "Geri çağırışı emal edin", description = "Epoint-dən ödəniş nəticələrini qəbul edir.")
    @PostMapping(value = {"/payment/result", "/payment/callback", "/payment/epoint/callback"})
    public ResponseEntity<String> handleCallback(
            @RequestParam("data") String data,
            @RequestParam("signature") String signature) {
        log.info("[Callback] (TRIGGERED) Callback endpoint was triggered with data length: {} and signature length: {}", data != null ? data.length() : 0, signature != null ? signature.length() : 0);
        log.info("[Callback] (ENTRY) Received callback: data={}, signature={}", data, signature);
        try {
            integrationService.processCallback(data, signature);
            log.info("[Callback] (EXIT) Successfully processed callback.");
            return ResponseEntity.ok("OK");
        } catch (IllegalArgumentException e) {
            log.warn("[Callback] Invalid callback request: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid request");
        } catch (SecurityException e) {
            log.warn("[Callback] Callback signature verification failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        } catch (Exception e) {
            log.error("[Callback] Error processing callback", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal server error");
        }
    }

    @Operation(summary = "Ödənişi başladın (v1)", description = "Köhnə axın / köhnə DTO: Coin endirimi yoxdur.")
    @PostMapping("/payment/payment/init")
    public ResponseEntity<EpointResponse> initiatePayment(
            @RequestBody CurrencyRequest currencyRequest,
            Authentication authentication) {
        return initiatePaymentInternal(currencyRequest, authentication, false);
    }

    @Operation(summary = "Ödənişi başladın (v2)", description = "Yeni axın / CurrencyRequestV2: isCoinUsed ilə Coin endirimi.")
    @PostMapping("/api/v2/payment/epoint/init")
    public ResponseEntity<EpointResponse> initiatePaymentV2(
            @RequestBody CurrencyRequestV2 currencyRequest,
            Authentication authentication) {
        return initiatePaymentInternal(currencyRequest.toV1(), authentication, currencyRequest.isCoinUsed());
    }

    private ResponseEntity<EpointResponse> initiatePaymentInternal(
            CurrencyRequest currencyRequest,
            Authentication authentication,
            Boolean isCoinUsed) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        log.info("[PaymentInit] (ENTRY) userId={}, packageId={}, optionId={}, isCoinUsed={}",
                userId, currencyRequest.packageId(), currencyRequest.optionId(), isCoinUsed);
        boolean hasPackageId = currencyRequest.packageId() != null;
        boolean hasOptionId = currencyRequest.optionId() != null;
        if (hasPackageId ^ hasOptionId) {
            log.warn("[PaymentInit] (ERROR) Both packageId and optionId must be provided together. packageId={}, optionId={}", currencyRequest.packageId(), currencyRequest.optionId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(EpointResponse.builder().status("error").message("Both packageId and optionId must be provided together").build());
        }
        if (hasPackageId && hasOptionId) {
            boolean valid = subscriptionPackageGrpcClient.checkOptionInPackageExists(currencyRequest.packageId(), currencyRequest.optionId());
            if (!valid) {
                log.warn("[PaymentInit] (ERROR) Invalid packageId or optionId. packageId={}, optionId={}", currencyRequest.packageId(), currencyRequest.optionId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(EpointResponse.builder().status("error").message("Invalid packageId or optionId").build());
            }
        }
        try {
            if (Boolean.TRUE.equals(currencyRequest.autoPaymentEnabled())) {
                var details = subscriptionPackageGrpcClient.getOptionPriceCurrency(currencyRequest.packageId(), currencyRequest.optionId());
                if (details.durationMonths != 1) {
                    log.warn("[PaymentInit] (ERROR) Auto-payment is only acceptable for 1-month duration. packageId={}, optionId={}, duration={}", currencyRequest.packageId(), currencyRequest.optionId(), details.durationMonths);
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(EpointResponse.builder().status("error").message("Avtomatik ödəniş yalnız 1 aylıq paketlər üçün keçərlidir").build());
                }
            }
            EpointResponse response = integrationService.initiatePayment(
                    userId,
                    currencyRequest.packageId(),
                    currencyRequest.optionId(),
                    currencyRequest.autoPaymentEnabled(),
                    isCoinUsed
            );
            log.info("[PaymentInit] (EXIT) userId={}, packageId={}, optionId={}, autoPay={}, isCoinUsed={}, status={}, message={}",
                    userId, currencyRequest.packageId(), currencyRequest.optionId(), currencyRequest.autoPaymentEnabled(), isCoinUsed, response.status(), response.message());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[PaymentInit] (ERROR) Exception occurred: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(EpointResponse.builder().status("error").message("Internal server error").build());
        }
    }

    @Operation(summary = "Kartın qeydiyyatı", description = "Yeni bir kartı sistemdə qeydiyyatdan keçirir.")
    @PostMapping("/payment/card/save-init")
    public ResponseEntity<EpointResponse> cardRegistration(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        EpointResponse response = integrationService.cardRegistration(userId);
        log.info("Card registration result: status={}, code={}, message={}, cardId={}, cardMask={}, cardName={}, bankTransaction={}, bankResponse={}, operationCode={}, rrn={}",
                response.status(),
                response.code(),
                response.message(),
                response.cardId(),
                response.cardMask(),
                response.cardName(),
                response.bankTransaction(),
                response.bankResponse(),
                response.operationCode(),
                response.rrn()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Ödənişlə kartın qeydiyyatı (v1)", description = "Köhnə axın / köhnə DTO: Coin endirimi yoxdur.")
    @PostMapping("/payment/payment/save-and-pay")
    public ResponseEntity<EpointResponse> cardRegistrationWithPay(
            @RequestBody CurrencyRequest currencyRequest,
            Authentication authentication) {
        return cardRegistrationWithPayInternal(currencyRequest, authentication, false);
    }

    @Operation(summary = "Ödənişlə kartın qeydiyyatı (v2)", description = "Yeni axın / CurrencyRequestV2: isCoinUsed ilə Coin endirimi.")
    @PostMapping("/api/v2/payment/epoint/save-and-pay")
    public ResponseEntity<EpointResponse> cardRegistrationWithPayV2(
            @RequestBody CurrencyRequestV2 currencyRequest,
            Authentication authentication) {
        return cardRegistrationWithPayInternal(currencyRequest.toV1(), authentication, currencyRequest.isCoinUsed());
    }

    private ResponseEntity<EpointResponse> cardRegistrationWithPayInternal(
            CurrencyRequest currencyRequest,
            Authentication authentication,
            Boolean isCoinUsed) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        boolean hasPackageId = currencyRequest.packageId() != null;
        boolean hasOptionId = currencyRequest.optionId() != null;
        if (hasPackageId ^ hasOptionId) {
            log.warn("[SaveAndPay] (ERROR) Both packageId and optionId must be provided together. packageId={}, optionId={}", currencyRequest.packageId(), currencyRequest.optionId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(EpointResponse.builder().status("error").message("Both packageId and optionId must be provided together").build());
        }
        if (hasPackageId && hasOptionId) {
            boolean valid = subscriptionPackageGrpcClient.checkOptionInPackageExists(currencyRequest.packageId(), currencyRequest.optionId());
            if (!valid) {
                log.warn("[SaveAndPay] (ERROR) Invalid packageId or optionId. packageId={}, optionId={}", currencyRequest.packageId(), currencyRequest.optionId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(EpointResponse.builder().status("error").message("Invalid packageId or optionId").build());
            }
        }
        try {
            var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(currencyRequest.packageId(), currencyRequest.optionId());
            if (Boolean.TRUE.equals(currencyRequest.autoPaymentEnabled()) && priceCurrency.durationMonths != 1) {
                log.warn("[SaveAndPay] (ERROR) Auto-payment is only acceptable for 1-month duration. packageId={}, optionId={}, duration={}", currencyRequest.packageId(), currencyRequest.optionId(), priceCurrency.durationMonths);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(EpointResponse.builder().status("error").message("Avtomatik ödəniş yalnız 1 aylıq paketlər üçün keçərlidir").build());
            }
            EpointResponse response = integrationService.cardRegistrationWithPay(
                    userId,
                    currencyRequest.packageId(),
                    currencyRequest.optionId(),
                    currencyRequest.autoPaymentEnabled(),
                    isCoinUsed);
            log.info("[SaveAndPay] (EXIT) userId={}, packageId={}, optionId={}, autoPay={}, isCoinUsed={}, status={}, message={}",
                    userId, currencyRequest.packageId(), currencyRequest.optionId(), currencyRequest.autoPaymentEnabled(), isCoinUsed, response.status(), response.message());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SaveAndPay] (ERROR) Exception occurred: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(EpointResponse.builder().status("error").message("Internal server error").build());
        }
    }

    @Operation(summary = "Yadda saxlanmış kartla ödəniş (v1)", description = "Köhnə axın / köhnə DTO: Coin endirimi yoxdur.")
    @PostMapping("/api/v1/payment/with-card")
    public ResponseEntity<ApiResponse<EpointResponse>> executePayWithCard(
            @RequestBody WithCardRequest withCardRequest,
            Authentication authentication,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        return executePayWithCardInternal(withCardRequest, authentication, httpRequest, false);
    }

    @Operation(summary = "Yadda saxlanmış kartla ödəniş (v2)", description = "Yeni axın / WithCardRequestV2: isCoinUsed ilə Coin endirimi.")
    @PostMapping("/api/v2/payment/epoint/with-card")
    public ResponseEntity<ApiResponse<EpointResponse>> executePayWithCardV2(
            @RequestBody WithCardRequestV2 withCardRequest,
            Authentication authentication,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        return executePayWithCardInternal(withCardRequest.toV1(), authentication, httpRequest, withCardRequest.isCoinUsed());
    }

    private ResponseEntity<ApiResponse<EpointResponse>> executePayWithCardInternal(
            WithCardRequest withCardRequest,
            Authentication authentication,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            Boolean isCoinUsed) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        boolean hasPackageId = withCardRequest.packageId() != null;
        boolean hasOptionId = withCardRequest.optionId() != null;
        boolean hasCardId = withCardRequest.cardId() != null && !withCardRequest.cardId().isBlank();
        if (!hasPackageId || !hasOptionId || !hasCardId) {
            log.warn("[WithCard] (ERROR) cardId, packageId and optionId must be provided. cardId={}, packageId={}, optionId={}", withCardRequest.cardId(), withCardRequest.packageId(), withCardRequest.optionId());
            ApiError apiError = ApiError.builder()
                    .code("error.payment.invalid_request")
                    .message("cardId, packageId and optionId must be provided")
                    .status(HttpStatus.BAD_REQUEST.value())
                    .path(httpRequest.getRequestURI())
                    .timestamp(java.time.OffsetDateTime.now())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(apiError));
        }
        boolean valid = subscriptionPackageGrpcClient.checkOptionInPackageExists(withCardRequest.packageId(), withCardRequest.optionId());
        if (!valid) {
            log.warn("[WithCard] (ERROR) Invalid packageId or optionId. packageId={}, optionId={}", withCardRequest.packageId(), withCardRequest.optionId());
            ApiError apiError = ApiError.builder()
                    .code("error.payment.invalid_request")
                    .message("Invalid packageId or optionId")
                    .status(HttpStatus.BAD_REQUEST.value())
                    .path(httpRequest.getRequestURI())
                    .timestamp(java.time.OffsetDateTime.now())
                    .build();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(apiError));
        }
        try {
            var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(withCardRequest.packageId(), withCardRequest.optionId());
            if (Boolean.TRUE.equals(withCardRequest.autoPaymentEnabled()) && priceCurrency.durationMonths != 1) {
                log.warn("[WithCard] (ERROR) Auto-payment is only acceptable for 1-month duration. packageId={}, optionId={}, duration={}", withCardRequest.packageId(), withCardRequest.optionId(), priceCurrency.durationMonths);
                ApiError apiError = ApiError.builder()
                    .code("error.payment.invalid_request")
                    .message("Avtomatik ödəniş yalnız 1 aylıq paketlər üçün keçərlidir")
                    .status(HttpStatus.BAD_REQUEST.value())
                    .path(httpRequest.getRequestURI())
                    .timestamp(java.time.OffsetDateTime.now())
                    .build();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(apiError));
            }
            EpointResponse response = integrationService.executePayWithCard(
                    userId,
                    withCardRequest.cardId(),
                    withCardRequest.packageId(),
                    withCardRequest.optionId(),
                    withCardRequest.autoPaymentEnabled(),
                    isCoinUsed);
            log.info("[WithCard] (EXIT) userId={}, cardId={}, packageId={}, optionId={}, autoPay={}, isCoinUsed={}, status={}, message={}",
                    userId, withCardRequest.cardId(), withCardRequest.packageId(), withCardRequest.optionId(), withCardRequest.autoPaymentEnabled(), isCoinUsed, response.status(), response.message());

            if (!"success".equalsIgnoreCase(response.status())) {
                String errorCode = response.code() != null ? response.code() : "error.payment.failed";
                String errorMessage = response.message() != null ? response.message() : "Payment failed";
                ApiError apiError = ApiError.builder()
                        .code(errorCode)
                        .message(errorMessage)
                        .status(HttpStatus.BAD_REQUEST.value())
                        .path(httpRequest.getRequestURI())
                        .timestamp(java.time.OffsetDateTime.now())
                        .build();
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(apiError));
            }

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            log.error("[WithCard] (ERROR) Exception occurred: {}", e.getMessage(), e);
            ApiError apiError = ApiError.builder()
                    .code("error.payment.internal_error")
                    .message("Internal server error")
                    .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                    .path(httpRequest.getRequestURI())
                    .timestamp(java.time.OffsetDateTime.now())
                    .build();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(apiError));
        }
    }

    @Operation(summary = "Vidcet URL-i yaradın (v1)", description = "Köhnə axın / köhnə DTO: Coin endirimi yoxdur.")
    @PostMapping("/payment/widget-url")
    public ResponseEntity<EpointResponse> createWidgetUrl(
            @RequestBody CurrencyRequest currencyRequest,
            Authentication authentication) {
        return createWidgetUrlInternal(currencyRequest, authentication, false);
    }

    @Operation(summary = "Vidcet URL-i yaradın (v2)", description = "Yeni axın / CurrencyRequestV2: isCoinUsed ilə Coin endirimi.")
    @PostMapping("/api/v2/payment/epoint/widget-url")
    public ResponseEntity<EpointResponse> createWidgetUrlV2(
            @RequestBody CurrencyRequestV2 currencyRequest,
            Authentication authentication) {
        return createWidgetUrlInternal(currencyRequest.toV1(), authentication, currencyRequest.isCoinUsed());
    }

    private ResponseEntity<EpointResponse> createWidgetUrlInternal(
            CurrencyRequest currencyRequest,
            Authentication authentication,
            Boolean isCoinUsed) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        log.info("[WidgetUrl] (ENTRY) userId={}, packageId={}, optionId={}, isCoinUsed={}",
                userId, currencyRequest.packageId(), currencyRequest.optionId(), isCoinUsed);

        if (currencyRequest.packageId() == null || currencyRequest.optionId() == null) {
            log.warn("[WidgetUrl] (ERROR) Both packageId and optionId must be provided.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(EpointResponse.builder().status("error").message("Both packageId and optionId must be provided").build());
        }

        try {
            var details = subscriptionPackageGrpcClient.getOptionPriceCurrency(currencyRequest.packageId(), currencyRequest.optionId());
            if (details == null) {
                log.warn("[WidgetUrl] (ERROR) Invalid packageId or optionId. packageId={}, optionId={}", currencyRequest.packageId(), currencyRequest.optionId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(EpointResponse.builder().status("error").message("Invalid packageId or optionId").build());
            }

            if (Boolean.TRUE.equals(currencyRequest.autoPaymentEnabled()) && details.durationMonths != 1) {
                log.warn("[WidgetUrl] (ERROR) Auto-payment is only acceptable for 1-month duration. packageId={}, optionId={}, duration={}", currencyRequest.packageId(), currencyRequest.optionId(), details.durationMonths);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(EpointResponse.builder().status("error").message("Avtomatik ödəniş yalnız 1 aylıq paketlər üçün keçərlidir").build());
            }

            EpointResponse response = integrationService.createWidgetUrl(
                    userId,
                    currencyRequest.packageId(),
                    currencyRequest.optionId(),
                    currencyRequest.autoPaymentEnabled(),
                    isCoinUsed);
            log.info("[WidgetUrl] (EXIT) userId={}, packageId={}, optionId={}, autoPay={}, isCoinUsed={}, status={}, widgetUrl={}",
                    userId, currencyRequest.packageId(), currencyRequest.optionId(), currencyRequest.autoPaymentEnabled(), isCoinUsed, response.status(), response.widgetUrl());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[WidgetUrl] (ERROR) Exception occurred: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(EpointResponse.builder().status("error").message("Internal server error").build());
        }
    }

    @Operation(summary = "Uğurlu ödənişdən sonra yönləndirmə", description = "İstifadəçini Epoint-dən uğurlu səhifəsinə yönləndirir")
    @GetMapping("/payment/redirect/success/{id}")
    public ResponseEntity<Void> redirectSuccess(@PathVariable("id") String id) {
        log.info("[Redirection] Redirecting success for id: {}", id);
        try {
            String targetUrl = integrationService.getSuccessRedirectUrl(id);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(java.net.URI.create(targetUrl))
                    .build();
        } catch (Exception e) {
            log.error("[Redirection] Error resolving success redirect URL for id: {}", id, e);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(java.net.URI.create(integrationService.getSuccessRedirectUrl()))
                    .build();
        }
    }

    @Operation(summary = "Uğursuz ödənişdən sonra yönləndirmə", description = "İstifadəçini Epoint-dən uğursuz səhifəsinə yönləndirir")
    @GetMapping("/payment/redirect/error/{id}")
    public ResponseEntity<Void> redirectError(@PathVariable("id") String id) {
        log.info("[Redirection] Redirecting error for id: {}", id);
        try {
            String targetUrl = integrationService.getErrorRedirectUrl(id);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(java.net.URI.create(targetUrl))
                    .build();
        } catch (Exception e) {
            log.error("[Redirection] Error resolving error redirect URL for id: {}", id, e);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(java.net.URI.create(integrationService.getErrorRedirectUrl()))
                    .build();
        }
    }

    @Operation(summary = "Callback probe", description = "Epoint-in GET yoxlaması üçün")
    @GetMapping(value = {"/payment/result", "/payment/callback", "/payment/epoint/callback"})
    public ResponseEntity<String> handleCallbackGet() {
        log.info("[Callback] GET probe received — returning OK");
        return ResponseEntity.ok("OK");
    }
}
