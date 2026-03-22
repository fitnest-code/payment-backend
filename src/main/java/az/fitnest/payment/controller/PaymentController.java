package az.fitnest.payment.controller;

import az.fitnest.payment.service.EpointIntegrationService;
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

@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
@Tag(name = "Ödənişlər", description = "Ödəniş inteqrasiyası üçün ucluqlar")
public class PaymentController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentController.class);

    private final EpointIntegrationService integrationService;

    @Operation(summary = "Geri çağırışı emal edin", description = "Epoint-dən ödəniş nəticələrini qəbul edir.")
    @PostMapping(value = {"/result", "/callback", "/epoint/callback"})
    public ResponseEntity<String> handleCallback(
            @RequestParam("data") String data,
            @RequestParam("signature") String signature) {
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

    @Operation(summary = "Ödənişi başladın", description = "Yeni bir ödəniş sorğusu yaradır. Yalnız məbləğ və valyuta göndərilir, digər sahələr serverdə doldurulur.")
    @PostMapping("/payment/init")
    public ResponseEntity<EpointResponse> initiatePayment(
            @RequestBody CurrencyRequest currencyRequest,
            Authentication authentication) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        return ResponseEntity.ok(integrationService.initiatePayment(currencyRequest.amount(), currencyRequest.currency(), userId));
    }

    @Operation(summary = "Tranzaksiya statusunu yoxlayın", description = "Tranzaksiyanın statusunu sorğulayır.")
    @GetMapping("/payment/status/{orderId}")
    public ResponseEntity<EpointResponse> getStatus(@PathVariable String orderId) {
        return ResponseEntity.ok(integrationService.getStatus(orderId));
    }

    @Operation(summary = "Kartın qeydiyyatı", description = "Yeni bir kartı sistemdə qeydiyyatdan keçirir.")
    @PostMapping("/card/save-init")
    public ResponseEntity<EpointResponse> cardRegistration(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        EpointResponse response = integrationService.cardRegistration(userId);
        log.info("Card registration result: status={}, message={}, cardId={}, redirectUrl={}", response.status(), response.message(), response.cardId(), response.redirectUrl());
        log.info("Success redirect URL: {}", integrationService.getSuccessRedirectUrl());
        log.info("Error redirect URL: {}", integrationService.getErrorRedirectUrl());
        log.info("Result callback URL: {}", integrationService.getResultCallbackUrl());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Yadda saxlanmış kartla ödəniş", description = "Yadda saxlanmış kartla ödəniş edir.")
    @PostMapping("/payment/with-card")
    public ResponseEntity<EpointResponse> executePay(
            @RequestBody PayWithCardRequest request,
            Authentication authentication) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        return ResponseEntity.ok(integrationService.executePay(request.amount(), request.currency(), request.cardId(), userId));
    }

    @Operation(summary = "Ödənişlə kartın qeydiyyatı", description = "Ödəniş zamanı kartı qeydiyyatdan keçirir. Yalnız məbləğ və valyuta göndərilir, digər sahələr serverdə doldurulur.")
    @PostMapping("/payment/save-and-pay")
    public ResponseEntity<EpointResponse> cardRegistrationWithPay(
            Authentication authentication,
            @RequestBody CurrencyRequest currencyRequest) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(integrationService.cardRegistrationWithPay(currencyRequest.amount(), currencyRequest.currency(), userId));
    }

    @Operation(summary = "Geri qaytarma sorğusu", description = "Ödənişin geri qaytarılmasını tələb edir.")
    @PostMapping("/refund")
    public ResponseEntity<EpointResponse> refundRequest(@RequestBody EpointRefundRequest request) {
        return ResponseEntity.ok(integrationService.refundRequest(request));
    }

    @Operation(summary = "Tranzaksiyanı geri qaytarın", description = "Tranzaksiyanı tam və ya qismən geri qaytarır (reverse). " +
            "Əgər göndərilən məbləğ orijinal məbləğdən azdırsa, qismən geri qaytarma (partial reversal) həyata keçirilir.")
    @PostMapping("/reverse")
    public ResponseEntity<EpointResponse> reverse(@RequestBody ReverseRequest request) {
        return ResponseEntity.ok(integrationService.reverse(request.transactionId(), request.amount(), request.currency()));
    }

    @Operation(summary = "Bölünmüş ödəniş sorğusu", description = "Bölünmüş (split) ödəniş yaradır.")
    @PostMapping("/payment/split-init")
    public ResponseEntity<EpointResponse> splitRequest(
            @RequestBody EpointSplitPaymentRequest request,
            Authentication authentication) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        return ResponseEntity.ok(integrationService.splitRequest(request.amount(), request.currency(), request.splitUser(), request.splitAmount(), userId));
    }

    @Operation(summary = "Bölünmüş ödənişi icra edin", description = "Bölünmüş ödənişi tamamlayır.")
    @PostMapping("/split/with-card")
    public ResponseEntity<EpointResponse> splitExecutePay(
            @RequestBody EpointSplitExecutePayRequest request,
            Authentication authentication) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        return ResponseEntity.ok(integrationService.splitExecutePay(request.amount(), request.currency(), request.cardId(), request.splitUser(), request.splitAmount(), userId));
    }

    @Operation(summary = "Bölünmüş ödənişlə kartın qeydiyyatı", description = "Bölünmüş ödəniş zamanı kartı qeydiyyatdan keçirir.")
    @PostMapping("/payment/split-save-and-pay")
    public ResponseEntity<EpointResponse> splitCardRegistrationWithPay(
            @RequestBody EpointSplitPaymentRequest request,
            Authentication authentication) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        return ResponseEntity.ok(integrationService.splitCardRegistrationWithPay(request.amount(), request.currency(), request.splitUser(), request.splitAmount(), userId));
    }

    @Operation(summary = "İlkin avtorizasiya sorğusu", description = "Vəsaitin bloklanması üçün ilkin avtorizasiya yaradır.")
    @PostMapping("/pre-auth-request")
    public ResponseEntity<EpointResponse> preAuthRequest(
            @RequestBody EpointPaymentRequest request,
            Authentication authentication) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        return ResponseEntity.ok(integrationService.preAuthRequest(request, userId));
    }

    @Operation(summary = "İlkin avtorizasiyanı tamamlayın", description = "Bloklanmış vəsaitin silinməsini tamamlayır.")
    @PostMapping("/pre-auth-complete")
    public ResponseEntity<EpointResponse> preAuthComplete(@RequestBody EpointPreAuthCompleteRequest request) {
        return ResponseEntity.ok(integrationService.preAuthComplete(request));
    }

    @Operation(summary = "Vidcet URL-i yaradın", description = "Ödəniş vidceti üçün keçid yaradır.")
    @PostMapping("/widget-url")
    public ResponseEntity<EpointResponse> createWidgetUrl(@RequestBody EpointWidgetRequest request) {
        return ResponseEntity.ok(integrationService.createWidgetUrl(request));
    }

    @Operation(summary = "Pul kisəsi statusu", description = "Epoint pul kisəsinin statusunu yoxlayır.")
    @GetMapping("/wallet/status")
    public ResponseEntity<EpointResponse> walletStatus() {
        return ResponseEntity.ok(integrationService.walletStatus());
    }

    @Operation(summary = "Pul kisəsi ilə ödəniş", description = "Epoint pul kisəsindən istifadə edərək ödəniş edir.")
    @PostMapping("/wallet/pay")
    public ResponseEntity<EpointResponse> walletPayment(@RequestBody EpointWalletPaymentRequest request, Authentication authentication) {
        Long userId = authentication != null ? (Long) authentication.getPrincipal() : null;
        return ResponseEntity.ok(integrationService.walletPayment(request, userId));
    }

    @Operation(summary = "Hesab-faktura yaradın", description = "Yeni ödəniş hesabı yaradır.")
    @PostMapping("/invoice/create")
    public ResponseEntity<EpointResponse> createInvoice(@RequestBody EpointInvoiceCreateRequest request) {
        return ResponseEntity.ok(integrationService.createInvoice(request));
    }

    @Operation(summary = "Hesab-fakturayı yeniləyin", description = "Mövcud hesabı yeniləyir.")
    @PostMapping("/invoice/update")
    public ResponseEntity<EpointResponse> updateInvoice(@RequestBody EpointInvoiceUpdateRequest request) {
        return ResponseEntity.ok(integrationService.updateInvoice(request));
    }

    @Operation(summary = "Hesab-fakturaya baxın", description = "Hesab haqqında məlumatı əldə edir.")
    @GetMapping("/invoice/view/{id}")
    public ResponseEntity<EpointResponse> viewInvoice(@PathVariable Long id) {
        return ResponseEntity.ok(integrationService.viewInvoice(id));
    }

    @Operation(summary = "Hesab-fakturaların siyahısı", description = "Bütün hesab-fakturaları sadalayır.")
    @GetMapping("/invoice/list")
    public ResponseEntity<EpointResponse> listInvoices(@RequestParam(required = false) String type,
                                                       @RequestParam(required = false) String order) {
        return ResponseEntity.ok(integrationService.listInvoices(type, order));
    }

    @Operation(summary = "SMS vasitəsilə hesabı göndərin", description = "Hesab-faktura linkini SMS ilə göndərir.")
    @PostMapping("/invoice/send-sms/{id}")
    public ResponseEntity<EpointResponse> sendInvoiceSms(@PathVariable Long id, @RequestParam String phone) {
        return ResponseEntity.ok(integrationService.sendInvoiceSms(id, phone));
    }

    @Operation(summary = "E-poçt vasitəsilə hesabı göndərin", description = "Hesab-faktura linkini e-poçt ilə göndərir.")
    @PostMapping("/invoice/send-email/{id}")
    public ResponseEntity<EpointResponse> sendInvoiceEmail(@PathVariable Long id, @RequestParam String email) {
        return ResponseEntity.ok(integrationService.sendInvoiceEmail(id, email));
    }

    @Operation(summary = "Heartbeat API", description = "Check Epoint service availability.")
    @GetMapping("/heartbeat")
    public ResponseEntity<EpointResponse> heartbeat() {
        return ResponseEntity.ok(integrationService.heartbeat());
    }
}
