package az.fitnest.payment.controller;

import az.fitnest.payment.client.epoint.EpointProperties;
import az.fitnest.payment.client.epoint.EpointSigner;
import az.fitnest.payment.service.EpointIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
}
