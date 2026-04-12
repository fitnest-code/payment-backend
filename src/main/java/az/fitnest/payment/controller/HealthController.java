package az.fitnest.payment.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Slf4j
@Tag(name = "Sağlamlıq Yoxlaması", description = "Xidmətin işləkliyini yoxlamaq üçün ucluqlar")
public class HealthController {

    @Operation(
            summary = "Heartbeat endpoint",
            description = "Provider-facing health check endpoint. Returns service status and timestamp."
    )
    @GetMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat() {
        log.debug("Heartbeat endpoint called");
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "payment-backend",
                "timestamp", Instant.now().toString()
        ));
    }
}
