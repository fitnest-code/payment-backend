package az.fitnest.payment.controller;

import az.fitnest.payment.client.epoint.EpointProperties;
import az.fitnest.payment.client.epoint.EpointSigner;
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
import az.fitnest.payment.dto.epoint.*;

@Slf4j
@RestController
@RequestMapping("/epoint")
@RequiredArgsConstructor
public class EpointController {

    private final EpointSigner signer;
    private final EpointIntegrationService integrationService;
    private final EpointProperties properties;

    @PostMapping("/result")
    public ResponseEntity<String> handleCallback(
            @RequestParam("data") String data,
            @RequestParam("signature") String signature) {
        
        log.info("Received Epoint callback");
        
        if (!signer.verify(data, signature, properties.getPrivateKey())) {
            log.error("Invalid Epoint signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        try {
            integrationService.processCallback(data);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error processing Epoint callback", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error");
        }
    }

    @PostMapping("/request")
    public ResponseEntity<EpointResponse> initiatePayment(@RequestBody EpointPaymentRequest request) {
        return ResponseEntity.ok(integrationService.initiatePayment(request));
    }

    @PostMapping("/card-registration")
    public ResponseEntity<EpointResponse> cardRegistration(@RequestBody EpointPaymentRequest request) {
        return ResponseEntity.ok(integrationService.cardRegistration(request));
    }

    @PostMapping("/execute-pay")
    public ResponseEntity<EpointResponse> executePay(@RequestBody EpointExecutePayRequest request) {
        return ResponseEntity.ok(integrationService.executePay(request));
    }

    @PostMapping("/card-registration-with-pay")
    public ResponseEntity<EpointResponse> cardRegistrationWithPay(@RequestBody EpointPaymentRequest request) {
        return ResponseEntity.ok(integrationService.cardRegistrationWithPay(request));
    }

    @PostMapping("/refund-request")
    public ResponseEntity<EpointResponse> refundRequest(@RequestBody EpointExecutePayRequest request) {
        return ResponseEntity.ok(integrationService.refundRequest(request));
    }

    @PostMapping("/reverse")
    public ResponseEntity<EpointResponse> reverse(@RequestParam String transactionId, 
                                                  @RequestParam Double amount, 
                                                  @RequestParam String currency) {
        return ResponseEntity.ok(integrationService.reverse(transactionId, amount, currency));
    }
    
    @PostMapping("/split-request")
    public ResponseEntity<EpointResponse> splitRequest(@RequestBody EpointSplitPaymentRequest request) {
        return ResponseEntity.ok(integrationService.splitRequest(request));
    }

    @PostMapping("/split-execute-pay")
    public ResponseEntity<EpointResponse> splitExecutePay(@RequestBody EpointSplitExecutePayRequest request) {
        return ResponseEntity.ok(integrationService.splitExecutePay(request));
    }

    @PostMapping("/split-card-registration-with-pay")
    public ResponseEntity<EpointResponse> splitCardRegistrationWithPay(@RequestBody EpointSplitPaymentRequest request) {
        return ResponseEntity.ok(integrationService.splitCardRegistrationWithPay(request));
    }

    @PostMapping("/pre-auth-request")
    public ResponseEntity<EpointResponse> preAuthRequest(@RequestBody EpointPaymentRequest request) {
        return ResponseEntity.ok(integrationService.preAuthRequest(request));
    }

    @PostMapping("/pre-auth-complete")
    public ResponseEntity<EpointResponse> preAuthComplete(@RequestBody EpointPreAuthCompleteRequest request) {
        return ResponseEntity.ok(integrationService.preAuthComplete(request));
    }

    @PostMapping("/widget-url")
    public ResponseEntity<EpointResponse> createWidgetUrl(@RequestBody EpointPaymentRequest request) {
        return ResponseEntity.ok(integrationService.createWidgetUrl(request));
    }

    @GetMapping("/wallet/status")
    public ResponseEntity<EpointResponse> walletStatus() {
        return ResponseEntity.ok(integrationService.walletStatus());
    }

    @PostMapping("/wallet/payment")
    public ResponseEntity<EpointResponse> walletPayment(@RequestBody EpointWalletPaymentRequest request) {
        return ResponseEntity.ok(integrationService.walletPayment(request));
    }

    @PostMapping("/invoices/create")
    public ResponseEntity<EpointResponse> createInvoice(@RequestBody EpointInvoiceCreateRequest request) {
        return ResponseEntity.ok(integrationService.createInvoice(request));
    }

    @PostMapping("/invoices/update")
    public ResponseEntity<EpointResponse> updateInvoice(@RequestBody EpointInvoiceUpdateRequest request) {
        return ResponseEntity.ok(integrationService.updateInvoice(request));
    }

    @GetMapping("/invoices/{id}")
    public ResponseEntity<EpointResponse> viewInvoice(@PathVariable Long id) {
        return ResponseEntity.ok(integrationService.viewInvoice(id));
    }

    @GetMapping("/invoices")
    public ResponseEntity<EpointResponse> listInvoices(@RequestParam(required = false) String type, 
                                                       @RequestParam(required = false) String order) {
        return ResponseEntity.ok(integrationService.listInvoices(type, order));
    }

    @PostMapping("/invoices/{id}/send-sms")
    public ResponseEntity<EpointResponse> sendInvoiceSms(@PathVariable Long id, @RequestParam String phone) {
        return ResponseEntity.ok(integrationService.sendInvoiceSms(id, phone));
    }

    @PostMapping("/invoices/{id}/send-email")
    public ResponseEntity<EpointResponse> sendInvoiceEmail(@PathVariable Long id, @RequestParam String email) {
        return ResponseEntity.ok(integrationService.sendInvoiceEmail(id, email));
    }
}
