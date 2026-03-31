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
            EpointResponse response = integrationService.initiatePayment(
                userId,
                currencyRequest.packageId(),
                currencyRequest.optionId()
            );
            log.info("[PaymentInit] (EXIT) userId={}, packageId={}, optionId={}, status={}, message={}", userId, currencyRequest.packageId(), currencyRequest.optionId(), response.status(), response.message());
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
            // Fetch amount and currency
            var priceCurrency = subscriptionPackageGrpcClient.getOptionPriceCurrency(currencyRequest.packageId(), currencyRequest.optionId());
            Double amount = priceCurrency.amount;
            String currency = priceCurrency.currency;
            // Build orderId and otherAttr
            String orderId = java.util.UUID.randomUUID().toString();
            java.util.List<String> otherAttrList = new java.util.ArrayList<>();
            if (currencyRequest.packageId() != null) otherAttrList.add("packageId:" + currencyRequest.packageId());
            if (currencyRequest.optionId() != null) otherAttrList.add("optionId:" + currencyRequest.optionId());
            String otherAttr = otherAttrList.isEmpty() ? null : String.join(",", otherAttrList);
            // Build payment request
            EpointPaymentRequest request = EpointPaymentRequest.builder()
                    .currency(currency != null ? currency : "AZN")
                    .amount(amount)
                    .language("az")
                    .orderId(orderId)
                    .description("Fitness package save and pay")
                    .isInstallment(0)
                    .refund(0)
                    .otherAttr(otherAttr)
                    .build();
            EpointResponse response = integrationService.cardRegistrationWithPay(userId, request);
            log.info("[SaveAndPay] (EXIT) userId={}, packageId={}, optionId={}, status={}, message={}", userId, currencyRequest.packageId(), currencyRequest.optionId(), response.status(), response.message());
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
            Double amount = priceCurrency.amount;
            String currency = priceCurrency.currency;
            String orderId = java.util.UUID.randomUUID().toString();
            // Store packageId and optionId in Redis with orderId as key for later association
            String redisKey = "payment:order:" + orderId;
            redisTemplate.opsForHash().put(redisKey, "packageId", String.valueOf(withCardRequest.packageId()));
            redisTemplate.opsForHash().put(redisKey, "optionId", String.valueOf(withCardRequest.optionId()));
            redisTemplate.expire(redisKey, java.time.Duration.ofHours(1)); // expire in 1 hour
            // Set public_key and language (hardcoded or from config)
            String publicKey = integrationService.getPublicKey(); // implement this method to fetch from config or env
            String language = "az";
            // Build description with packageId and optionId for downstream assignment
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
                    .build();
            EpointResponse response = integrationService.executePay(request, userId);
            log.info("[WithCard] (EXIT) userId={}, cardId={}, packageId={}, optionId={}, status={}, message={}", userId, withCardRequest.cardId(), withCardRequest.packageId(), withCardRequest.optionId(), response.status(), response.message());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[WithCard] (ERROR) Exception occurred: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(EpointResponse.builder().status("error").message("Internal server error").build());
        }
    }

    // @Operation(summary = "Geri qaytarma sorğusu", description = "Ödənişin geri qaytarılmasını tələb edir.")
    // @PostMapping("/refund")
    // public ResponseEntity<EpointResponse> refundRequest(@RequestBody EpointRefundRequest request) {
    //     return ResponseEntity.ok(integrationService.refundRequest(request));
    // }

    // @Operation(summary = "Tranzaksiyanı geri qaytarın", description = "Tranzaksiyanı tam və ya qismən geri qaytarır (reverse). " +
    //         "Əgər göndərilən məbləğ orijinal məbləğdən azdırsa, qismən geri qaytarma (partial reversal) həyata keçirilir.")
    // @PostMapping("/reverse")
    // public ResponseEntity<EpointResponse> reverse(@RequestBody ReverseRequest request) {
    //     return ResponseEntity.ok(integrationService.reverse(request.transactionId(), request.amount(), request.currency()));
    // }

    // @Operation(summary = "Bölünmüş ödəniş sorğusu", description = "Bölünmüş (split) ödəniş yaradır.")
    // @PostMapping("/payment/split-init")
    // public ResponseEntity<EpointResponse> splitRequest(
    //         @RequestBody EpointSplitPaymentRequest request,
    //         Authentication authentication) {
    //     Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
    //     return ResponseEntity.ok(integrationService.splitRequest(request, userId));
    // }

    // @Operation(summary = "Bölünmüş ödənişi icra edin", description = "Bölünmüş ödənişi tamamlayır.")
    // @PostMapping("/split/with-card")
    // public ResponseEntity<EpointResponse> splitExecutePay(
    //         @RequestBody EpointSplitExecutePayRequest request,
    //         Authentication authentication) {
    //     Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
    //     return ResponseEntity.ok(integrationService.splitExecutePay(request, userId));
    // }

    // @Operation(summary = "Bölünmüş ödənişlə kartın qeydiyyatı", description = "Bölünmüş ödəniş zamanı kartı qeydiyyatdan keçirir.")
    // @PostMapping("/payment/split-save-and-pay")
    // public ResponseEntity<EpointResponse> splitCardRegistrationWithPay(
    //         @RequestBody EpointSplitPaymentRequest request,
    //         Authentication authentication) {
    //     Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
    //     return ResponseEntity.ok(integrationService.splitCardRegistrationWithPay(request, userId));
    // }

    // @Operation(summary = "İlkin avtorizasiya sorğusu", description = "Vəsaitin bloklanması üçün ilkin avtorizasiya yaradır.")
    // @PostMapping("/pre-auth-request")
    // public ResponseEntity<EpointResponse> preAuthRequest(
    //         @RequestBody EpointPaymentRequest request,
    //         Authentication authentication) {
    //     Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
    //     return ResponseEntity.ok(integrationService.preAuthRequest(request, userId));
    // }

    // @Operation(summary = "İlkin avtorizasiyanı tamamlayın", description = "Bloklanmış vəsaitin silinməsini tamamlayır.")
    // @PostMapping("/pre-auth-complete")
    // public ResponseEntity<EpointResponse> preAuthComplete(@RequestBody EpointPreAuthCompleteRequest request) {
    //     return ResponseEntity.ok(integrationService.preAuthComplete(request));
    // }

    // @Operation(summary = "Vidcet URL-i yaradın", description = "Ödəniş vidceti üçün keçid yaradır.")
    // @PostMapping("/widget-url")
    // public ResponseEntity<EpointResponse> createWidgetUrl(@RequestBody EpointWidgetRequest request) {
    //     return ResponseEntity.ok(integrationService.createWidgetUrl(request));
    // }

    // @Operation(summary = "Pul kisəsi statusu", description = "Epoint pul kisəsinin statusunu yoxlayır.")
    // @GetMapping("/wallet/status")
    // public ResponseEntity<EpointResponse> walletStatus() {
    //     return ResponseEntity.ok(integrationService.walletStatus());
    // }

    // @Operation(summary = "Pul kisəsi ilə ödəniş", description = "Epoint pul kisəsindən istifadə edərək ödəniş edir.")
    // @PostMapping("/wallet/pay")
    // public ResponseEntity<EpointResponse> walletPayment(@RequestBody EpointWalletPaymentRequest request, Authentication authentication) {
    //     Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
    //     return ResponseEntity.ok(integrationService.walletPayment(request, userId));
    // }

    // @Operation(summary = "Hesab-faktura yaradın", description = "Yeni ödəniş hesabı yaradır.")
    // @PostMapping("/invoice/create")
    // public ResponseEntity<EpointResponse> createInvoice(@RequestBody EpointInvoiceCreateRequest request) {
    //     return ResponseEntity.ok(integrationService.createInvoice(request));
    // }

    // @Operation(summary = "Hesab-fakturayı yeniləyin", description = "Mövcud hesabı yeniləyir.")
    // @PostMapping("/invoice/update")
    // public ResponseEntity<EpointResponse> updateInvoice(@RequestBody EpointInvoiceUpdateRequest request) {
    //     return ResponseEntity.ok(integrationService.updateInvoice(request));
    // }

    // @Operation(summary = "Hesab-faktura baxın", description = "Hesab haqqında məlumatı əldə edir.")
    // @GetMapping("/invoice/view/{id}")
    // public ResponseEntity<EpointResponse> viewInvoice(@PathVariable Long id) {
    //     return ResponseEntity.ok(integrationService.viewInvoice(id));
    // }

    // @Operation(summary = "Hesab-fakturaların siyahısı", description = "Bütün hesab-fakturaları sadalayır.")
    // @GetMapping("/invoice/list")
    // public ResponseEntity<EpointResponse> listInvoices(@RequestParam(required = false) String type,
    //                                                    @RequestParam(required = false) String order) {
    //     return ResponseEntity.ok(integrationService.listInvoices(type, order));
    // }

    // @Operation(summary = "SMS vasitəsilə hesabı göndərin", description = "Hesab-faktura linkini SMS ilə göndərir.")
    // @PostMapping("/invoice/send-sms/{id}")
    // public ResponseEntity<EpointResponse> sendInvoiceSms(@PathVariable Long id, @RequestParam String phone) {
    //     return ResponseEntity.ok(integrationService.sendInvoiceSms(id, phone));
    // }

    // @Operation(summary = "E-poçt vasitəsilə hesabı göndərin", description = "Hesab-faktura linkini e-poçt ilə göndərir.")
    // @PostMapping("/invoice/send-email/{id}")
    // public ResponseEntity<EpointResponse> sendInvoiceEmail(@PathVariable Long id, @RequestParam String email) {
    //     return ResponseEntity.ok(integrationService.sendInvoiceEmail(id, email));
    // }

    // @Operation(summary = "Heartbeat API", description = "Check Epoint service availability.")
    // @GetMapping("/heartbeat")
    // public ResponseEntity<EpointResponse> heartbeat() {
    //     return ResponseEntity.ok(integrationService.heartbeat());
    // }
}
