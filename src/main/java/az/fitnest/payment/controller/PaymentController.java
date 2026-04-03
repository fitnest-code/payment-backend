package az.fitnest.payment.controller;

import az.fitnest.payment.service.EpointIntegrationService;
import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
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
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.redis.core.StringRedisTemplate;

@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
@Tag(name = "Ödənişlər", description = "Ödəniş inteqrasiyası üçün ucluqlar")
public class PaymentController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentController.class);

    private final EpointIntegrationService integrationService;
    private final StringRedisTemplate redisTemplate;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    @Operation(summary = "Geri çağırışı emal edin", description = "Epoint-dən ödəniş nəticələrini qəbul edir.")
    @PostMapping(value = {"/result", "/callback", "/epoint/callback"})
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

    @Operation(summary = "Ödənişi başladın", description = "Yeni bir ödəniş sorğusu yaradır. Yalnız packageId və optionId göndərilir, məbləğ və valyuta serverdə müəyyən edilir.")
    @PostMapping("/payment/init")
    public ResponseEntity<EpointResponse> initiatePayment(
            @RequestBody CurrencyRequest currencyRequest,
            Authentication authentication) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        log.info("[PaymentInit] (ENTRY) userId={}, packageId={}, optionId={}", userId, currencyRequest.packageId(), currencyRequest.optionId());
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
                currencyRequest.autoPaymentEnabled()
            );
            log.info("[PaymentInit] (EXIT) userId={}, packageId={}, optionId={}, autoPay={}, status={}, message={}", userId, currencyRequest.packageId(), currencyRequest.optionId(), currencyRequest.autoPaymentEnabled(), response.status(), response.message());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[PaymentInit] (ERROR) Exception occurred: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(EpointResponse.builder().status("error").message("Internal server error").build());
        }
    }

    @Operation(summary = "Kartın qeydiyyatı", description = "Yeni bir kartı sistemdə qeydiyyatdan keçirir.")
    @PostMapping("/card/save-init")
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

    @Operation(summary = "Ödənişlə kartın qeydiyyatı", description = "Ödəniş zamanı kartı qeydiyyatdan keçirir. Yalnız məbləğ və valyuta göndərilir, digər sahələr serverdə doldurulur.")
    @PostMapping("/payment/save-and-pay")
    public ResponseEntity<EpointResponse> cardRegistrationWithPay(
            @RequestBody CurrencyRequest currencyRequest,
            Authentication authentication) {
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
            Double amount = priceCurrency.amount;
            String currency = priceCurrency.currency;
            String orderId = java.util.UUID.randomUUID().toString();
            java.util.List<String> otherAttrList = new java.util.ArrayList<>();
            if (currencyRequest.packageId() != null) otherAttrList.add("packageId:" + currencyRequest.packageId());
            if (currencyRequest.optionId() != null) otherAttrList.add("optionId:" + currencyRequest.optionId());
            String otherAttr = otherAttrList.isEmpty() ? null : String.join(",", otherAttrList);
            EpointPaymentRequest request = EpointPaymentRequest.builder()
                    .currency(currency != null ? currency : "AZN")
                    .amount(amount)
                    .language("az")
                    .orderId(orderId)
                    .description("Fitness package save and pay")
                    .isInstallment(0)
                    .refund(0)
                    .otherAttr(otherAttr)
                    .autoPaymentEnabled(currencyRequest.autoPaymentEnabled())
                    .build();
            EpointResponse response = integrationService.cardRegistrationWithPay(userId, request);
            log.info("[SaveAndPay] (EXIT) userId={}, packageId={}, optionId={}, autoPay={}, status={}, message={}", userId, currencyRequest.packageId(), currencyRequest.optionId(), currencyRequest.autoPaymentEnabled(), response.status(), response.message());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[SaveAndPay] (ERROR) Exception occurred: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(EpointResponse.builder().status("error").message("Internal server error").build());
        }
    }

    @Operation(summary = "Yadda saxlanmış kartla ödəniş", description = "Yadda saxlanmış kartla ödəniş edir. CardId, packageId və optionId göndərilir, məbləğ və valyuta serverdə müəyyən edilir.")
    @PostMapping("/payment/with-card")
    public ResponseEntity<EpointResponse> executePayWithCard(
            @RequestBody WithCardRequest withCardRequest,
            Authentication authentication) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        boolean hasPackageId = withCardRequest.packageId() != null;
        boolean hasOptionId = withCardRequest.optionId() != null;
        boolean hasCardId = withCardRequest.cardId() != null && !withCardRequest.cardId().isBlank();
        if (!hasPackageId || !hasOptionId || !hasCardId) {
            log.warn("[WithCard] (ERROR) cardId, packageId and optionId must be provided. cardId={}, packageId={}, optionId={}", withCardRequest.cardId(), withCardRequest.packageId(), withCardRequest.optionId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(EpointResponse.builder().status("error").message("cardId, packageId and optionId must be provided").build());
        }
        boolean valid = subscriptionPackageGrpcClient.checkOptionInPackageExists(withCardRequest.packageId(), withCardRequest.optionId());
        if (!valid) {
            log.warn("[WithCard] (ERROR) Invalid packageId or optionId. packageId={}, optionId={}", withCardRequest.packageId(), withCardRequest.optionId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(EpointResponse.builder().status("error").message("Invalid packageId or optionId").build());
        }
        try {
            var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(withCardRequest.packageId(), withCardRequest.optionId());
            if (Boolean.TRUE.equals(withCardRequest.autoPaymentEnabled()) && priceCurrency.durationMonths != 1) {
                log.warn("[WithCard] (ERROR) Auto-payment is only acceptable for 1-month duration. packageId={}, optionId={}, duration={}", withCardRequest.packageId(), withCardRequest.optionId(), priceCurrency.durationMonths);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(EpointResponse.builder().status("error").message("Avtomatik ödəniş yalnız 1 aylıq paketlər üçün keçərlidir").build());
            }
            Double amount = priceCurrency.amount;
            String currency = priceCurrency.currency;
            String orderId = java.util.UUID.randomUUID().toString();
            String redisKey = "payment:order:" + orderId;
            redisTemplate.opsForHash().put(redisKey, "packageId", String.valueOf(withCardRequest.packageId()));
            redisTemplate.opsForHash().put(redisKey, "optionId", String.valueOf(withCardRequest.optionId()));
            redisTemplate.expire(redisKey, java.time.Duration.ofHours(1));
            String publicKey = integrationService.getPublicKey();
            String language = "az";
            String description = "packageId:" + withCardRequest.packageId() + ",optionId:" + withCardRequest.optionId();
            EpointExecutePayRequest request = EpointExecutePayRequest.builder()
                    .publicKey(publicKey)
                    .language(language)
                    .cardId(withCardRequest.cardId())
                    .orderId(orderId)
                    .amount(amount)
                    .currency(currency != null ? currency : "AZN")
                    .description(description)
                    .isInstallment(0)
                    .autoPaymentEnabled(withCardRequest.autoPaymentEnabled())
                    .build();
            EpointResponse response = integrationService.executePay(request, userId);
            log.info("[WithCard] (EXIT) userId={}, cardId={}, packageId={}, optionId={}, autoPay={}, status={}, message={}", userId, withCardRequest.cardId(), withCardRequest.packageId(), withCardRequest.optionId(), withCardRequest.autoPaymentEnabled(), response.status(), response.message());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[WithCard] (ERROR) Exception occurred: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(EpointResponse.builder().status("error").message("Internal server error").build());
        }
    }

    @Operation(summary = "Vidcet URL-i yaradın", description = "Apple Pay və Google Pay üçün ödəniş vidceti linki yaradır.")
    @PostMapping("/widget-url")
    public ResponseEntity<EpointResponse> createWidgetUrl(
            @RequestBody CurrencyRequest currencyRequest,
            Authentication authentication) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        log.info("[WidgetUrl] (ENTRY) userId={}, packageId={}, optionId={}", userId, currencyRequest.packageId(), currencyRequest.optionId());

        if (currencyRequest.packageId() == null || currencyRequest.optionId() == null) {
            log.warn("[WidgetUrl] (ERROR) Both packageId and optionId must be provided.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(EpointResponse.builder().status("error").message("Both packageId and optionId must be provided").build());
        }

        boolean valid = subscriptionPackageGrpcClient.checkOptionInPackageExists(currencyRequest.packageId(), currencyRequest.optionId());
        if (!valid) {
            log.warn("[WidgetUrl] (ERROR) Invalid packageId or optionId. packageId={}, optionId={}", currencyRequest.packageId(), currencyRequest.optionId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(EpointResponse.builder().status("error").message("Invalid packageId or optionId").build());
        }

        try {
            if (Boolean.TRUE.equals(currencyRequest.autoPaymentEnabled())) {
                var details = subscriptionPackageGrpcClient.getOptionPriceCurrency(currencyRequest.packageId(), currencyRequest.optionId());
                if (details.durationMonths != 1) {
                    log.warn("[WidgetUrl] (ERROR) Auto-payment is only acceptable for 1-month duration. packageId={}, optionId={}, duration={}", currencyRequest.packageId(), currencyRequest.optionId(), details.durationMonths);
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(EpointResponse.builder().status("error").message("Avtomatik ödəniş yalnız 1 aylıq paketlər üçün keçərlidir").build());
                }
            }
            EpointResponse response = integrationService.createWidgetUrl(userId, currencyRequest.packageId(), currencyRequest.optionId(), currencyRequest.autoPaymentEnabled());
            log.info("[WidgetUrl] (EXIT) userId={}, packageId={}, optionId={}, autoPay={}, status={}, widgetUrl={}", userId, currencyRequest.packageId(), currencyRequest.optionId(), currencyRequest.autoPaymentEnabled(), response.status(), response.widgetUrl());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[WidgetUrl] (ERROR) Exception occurred: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(EpointResponse.builder().status("error").message("Internal server error").build());
        }
    }

}
