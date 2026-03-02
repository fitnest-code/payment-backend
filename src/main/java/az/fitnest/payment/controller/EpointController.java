package az.fitnest.payment.controller;

import az.fitnest.payment.client.epoint.EpointProperties;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.service.EpointIntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import az.fitnest.payment.dto.epoint.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/epoint")
@RequiredArgsConstructor
@Tag(name = "Epoint Ödənişləri", description = "Epoint.az ödəniş inteqrasiyası üçün ucluqlar")
public class EpointController {

    private final EpointSigner signer;
    private final EpointIntegrationService integrationService;
    private final EpointProperties properties;

    @Operation(summary = "Geri çağırışı emal edin", description = "Epoint-dən ödəniş nəticələrini qəbul edir.")
    @PostMapping("/result")
    public ResponseEntity<String> handleCallback(
            @RequestParam("data") String data,
            @RequestParam("signature") String signature) {


        if (!signer.verify(data, signature, properties.getPrivateKey())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        try {
            integrationService.processCallback(data);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error");
        }
    }

    @Operation(summary = "Ödənişi başladın", description = "Yeni bir ödəniş sorğusu yaradır.")
    @PostMapping("/request")
    public ResponseEntity<EpointResponse> initiatePayment(@RequestBody EpointPaymentRequest request) {
        return ResponseEntity.ok(integrationService.initiatePayment(request));
    }

    @Operation(summary = "Kartın qeydiyyatı", description = "Yeni bir kartı sistemdə qeydiyyatdan keçirir.")
    @PostMapping("/card-registration")
    public ResponseEntity<EpointResponse> cardRegistration(@RequestBody EpointPaymentRequest request) {
        return ResponseEntity.ok(integrationService.cardRegistration(request));
    }

    @Operation(summary = "Ödənişi icra edin", description = "Mövcud kartla ödənişi icra edir.")
    @PostMapping("/execute-pay")
    public ResponseEntity<EpointResponse> executePay(@RequestBody EpointExecutePayRequest request) {
        return ResponseEntity.ok(integrationService.executePay(request));
    }

    @Operation(summary = "Ödənişlə kartın qeydiyyatı", description = "Ödəniş zamanı kartı qeydiyyatdan keçirir.")
    @PostMapping("/card-registration-with-pay")
    public ResponseEntity<EpointResponse> cardRegistrationWithPay(@RequestBody EpointPaymentRequest request) {
        return ResponseEntity.ok(integrationService.cardRegistrationWithPay(request));
    }

    @Operation(summary = "Geri qaytarma sorğusu", description = "Ödənişin geri qaytarılmasını tələb edir.")
    @PostMapping("/refund-request")
    public ResponseEntity<EpointResponse> refundRequest(@RequestBody EpointExecutePayRequest request) {
        return ResponseEntity.ok(integrationService.refundRequest(request));
    }

    @Operation(summary = "Ödənişi ləğv edin", description = "Tranzaksiyanı ləğv edir.")
    @PostMapping("/reverse")
    public ResponseEntity<EpointResponse> reverse(@RequestParam String transactionId,
                                                  @RequestParam Double amount,
                                                  @RequestParam String currency) {
        return ResponseEntity.ok(integrationService.reverse(transactionId, amount, currency));
    }

    @Operation(summary = "Bölünmüş ödəniş sorğusu", description = "Bölünmüş (split) ödəniş yaradır.")
    @PostMapping("/split-request")
    public ResponseEntity<EpointResponse> splitRequest(@RequestBody EpointSplitPaymentRequest request) {
        return ResponseEntity.ok(integrationService.splitRequest(request));
    }

    @Operation(summary = "Bölünmüş ödənişi icra edin", description = "Bölünmüş ödənişi tamamlayır.")
    @PostMapping("/split-execute-pay")
    public ResponseEntity<EpointResponse> splitExecutePay(@RequestBody EpointSplitExecutePayRequest request) {
        return ResponseEntity.ok(integrationService.splitExecutePay(request));
    }

    @Operation(summary = "Bölünmüş ödənişlə kartın qeydiyyatı", description = "Bölünmüş ödəniş zamanı kartı qeydiyyatdan keçirir.")
    @PostMapping("/split-card-registration-with-pay")
    public ResponseEntity<EpointResponse> splitCardRegistrationWithPay(@RequestBody EpointSplitPaymentRequest request) {
        return ResponseEntity.ok(integrationService.splitCardRegistrationWithPay(request));
    }

    @Operation(summary = "İlkin avtorizasiya sorğusu", description = "Vəsaitin bloklanması üçün ilkin avtorizasiya yaradır.")
    @PostMapping("/pre-auth-request")
    public ResponseEntity<EpointResponse> preAuthRequest(@RequestBody EpointPaymentRequest request) {
        return ResponseEntity.ok(integrationService.preAuthRequest(request));
    }

    @Operation(summary = "İlkin avtorizasiyanı tamamlayın", description = "Bloklanmış vəsaitin silinməsini tamamlayır.")
    @PostMapping("/pre-auth-complete")
    public ResponseEntity<EpointResponse> preAuthComplete(@RequestBody EpointPreAuthCompleteRequest request) {
        return ResponseEntity.ok(integrationService.preAuthComplete(request));
    }

    @Operation(summary = "Vidcet URL-i yaradın", description = "Ödəniş vidceti üçün keçid yaradır.")
    @PostMapping("/widget-url")
    public ResponseEntity<EpointResponse> createWidgetUrl(@RequestBody EpointPaymentRequest request) {
        return ResponseEntity.ok(integrationService.createWidgetUrl(request));
    }

    @Operation(summary = "Pul kisəsi statusu", description = "Epoint pul kisəsinin statusunu yoxlayır.")
    @GetMapping("/wallet/status")
    public ResponseEntity<EpointResponse> walletStatus() {
        return ResponseEntity.ok(integrationService.walletStatus());
    }

    @Operation(summary = "Pul kisəsi ilə ödəniş", description = "Epoint pul kisəsindən istifadə edərək ödəniş edir.")
    @PostMapping("/wallet/payment")
    public ResponseEntity<EpointResponse> walletPayment(@RequestBody EpointWalletPaymentRequest request) {
        return ResponseEntity.ok(integrationService.walletPayment(request));
    }

    @Operation(summary = "Hesab-faktura yaradın", description = "Yeni ödəniş hesabı yaradır.")
    @PostMapping("/invoices/create")
    public ResponseEntity<EpointResponse> createInvoice(@RequestBody EpointInvoiceCreateRequest request) {
        return ResponseEntity.ok(integrationService.createInvoice(request));
    }

    @Operation(summary = "Hesab-fakturayı yeniləyin", description = "Mövcud hesabı yeniləyir.")
    @PostMapping("/invoices/update")
    public ResponseEntity<EpointResponse> updateInvoice(@RequestBody EpointInvoiceUpdateRequest request) {
        return ResponseEntity.ok(integrationService.updateInvoice(request));
    }

    @Operation(summary = "Hesab-fakturaya baxın", description = "Hesab haqqında məlumatı əldə edir.")
    @GetMapping("/invoices/{id}")
    public ResponseEntity<EpointResponse> viewInvoice(@PathVariable Long id) {
        return ResponseEntity.ok(integrationService.viewInvoice(id));
    }

    @Operation(summary = "Hesab-fakturaların siyahısı", description = "Bütün hesab-fakturaları sadalayır.")
    @GetMapping("/invoices")
    public ResponseEntity<EpointResponse> listInvoices(@RequestParam(required = false) String type,
                                                       @RequestParam(required = false) String order) {
        return ResponseEntity.ok(integrationService.listInvoices(type, order));
    }

    @Operation(summary = "SMS vasitəsilə hesabı göndərin", description = "Hesab-faktura linkini SMS ilə göndərir.")
    @PostMapping("/invoices/{id}/send-sms")
    public ResponseEntity<EpointResponse> sendInvoiceSms(@PathVariable Long id, @RequestParam String phone) {
        return ResponseEntity.ok(integrationService.sendInvoiceSms(id, phone));
    }

    @Operation(summary = "E-poçt vasitəsilə hesabı göndərin", description = "Hesab-faktura linkini e-poçt ilə göndərir.")
    @PostMapping("/invoices/{id}/send-email")
    public ResponseEntity<EpointResponse> sendInvoiceEmail(@PathVariable Long id, @RequestParam String email) {
        return ResponseEntity.ok(integrationService.sendInvoiceEmail(id, email));
    }
}
