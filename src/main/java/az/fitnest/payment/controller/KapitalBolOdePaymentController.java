package az.fitnest.payment.controller;

import az.fitnest.payment.dto.kapital.bolode.*;
import az.fitnest.payment.exception.BolOdeActiveApplicationException;
import az.fitnest.payment.service.kapital.bolode.BolOdeApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kapital Bank "Böl ödə" manual application endpoints (mobile).
 * Dual mapping mirrors the other providers: direct path and the gateway-forwarded /api/v2 path.
 */
@RestController
@RequestMapping({"/payment/kapital/bol-ode", "/api/v2/payment/kapital/bol-ode"})
@RequiredArgsConstructor
@Tag(name = "Kapital Böl ödə", description = "Kapital Bank Böl ödə manual application workflow")
public class KapitalBolOdePaymentController {

    private final BolOdeApplicationService service;

    @Operation(summary = "Böl ödə müraciəti yarat",
            description = "409: aktiv müraciət artıq var (body-də id/displayId).")
    @PostMapping("/init")
    public ResponseEntity<?> init(@RequestBody BolOdeInitRequest request, Authentication authentication) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.init(userId(authentication), request));
        } catch (BolOdeActiveApplicationException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("errorCode", e.getErrorCode());
            body.put("message", e.getMessage());
            body.put("id", e.getApplicationId());
            body.put("displayId", e.getDisplayId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
    }

    @Operation(summary = "İstifadəçinin ən son Böl ödə müraciəti (204 = yoxdur)")
    @GetMapping("/me")
    public ResponseEntity<BolOdeApplicationResponse> me(Authentication authentication) {
        return service.findMine(userId(authentication))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "İstifadəçinin bütün Böl ödə müraciətləri (ən yenidən)")
    @GetMapping("/me/list")
    public java.util.List<BolOdeApplicationResponse> meList(Authentication authentication) {
        return service.listMine(userId(authentication));
    }

    @GetMapping("/{id}")
    public BolOdeApplicationResponse detail(@PathVariable Long id, Authentication authentication) {
        return service.getMine(userId(authentication), id);
    }

    @Operation(summary = "Status polling (id, displayId və ya paymentReference)")
    @GetMapping("/status/{id}")
    public BolOdeApplicationSummary status(@PathVariable String id, Authentication authentication) {
        return service.getStatus(userId(authentication), id);
    }

    @Operation(summary = "Müraciəti ləğv et (yalnız NEW/PENDING/IN_REVIEW)")
    @PostMapping("/{id}/cancel")
    public BolOdeApplicationResponse cancel(@PathVariable Long id, Authentication authentication) {
        return service.cancel(userId(authentication), id);
    }

    // Same principal convention as BnplPaymentController (Long set by FitnestSecurityFilter).
    private Long userId(Authentication authentication) {
        return authentication != null ? (Long) authentication.getPrincipal() : null;
    }
}
