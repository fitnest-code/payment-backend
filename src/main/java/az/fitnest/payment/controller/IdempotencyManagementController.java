package az.fitnest.payment.controller;

import az.fitnest.payment.service.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin endpoints for managing idempotency keys.
 */
@RestController
@RequestMapping("/api/v1/admin/idempotency")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Idempotency Management (Admin)", description = "Idempotency key yönetimi üçün admin ucluqları")
@SecurityRequirement(name = "bearerAuth")
public class IdempotencyManagementController {

    private final IdempotencyService idempotencyService;

    /**
     * Get idempotency system statistics
     */
    @Operation(
            summary = "Idempotency statistikasını əldə edin",
            description = "Aktiv açarların sayı, TTL və digər məlumatları göstərir"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = IdempotencyService.IdempotencyStats.class)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "403", description = "Admin icazəsi tələb olunur")
    })
    @GetMapping("/stats")
    public ResponseEntity<IdempotencyService.IdempotencyStats> getStats() {
        return ResponseEntity.ok(idempotencyService.getStats());
    }

    /**
     * Get details of a specific idempotency key
     */
    @Operation(
            summary = "Idempotency açarının detallarını əldə edin",
            description = "Müəyyən bir açar haqqında məlumat göstərir (status, response body, etc.)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(mediaType = "application/json")
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "403", description = "Admin icazəsi tələb olunur"),
            @ApiResponse(responseCode = "404", description = "Açar tapılmadı")
    })
    @GetMapping("/key/{idempotencyKey}")
    public ResponseEntity<?> getKeyDetails(
            @Parameter(description = "Idempotency açarı (məs: payment:ORD12345:100)")
            @PathVariable String idempotencyKey) {
        return idempotencyService.getIdempotencyKeyRecord(idempotencyKey)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Clear Redis cache for a specific key (debugging)
     */
    @Operation(
            summary = "Spesifik açarın Redis cache-ini sil",
            description = "Test və debug məqsədləri üçün. Verilənlər bazasında saxlanılır."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cache sıfırlandı"),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "403", description = "Admin icazəsi tələb olunur")
    })
    @DeleteMapping("/cache/{idempotencyKey}")
    public ResponseEntity<Map<String, String>> clearCacheForKey(
            @Parameter(description = "Idempotency açarı")
            @PathVariable String idempotencyKey) {
        idempotencyService.clearRedisCache(idempotencyKey);
        return ResponseEntity.ok(Map.of(
                "message", "Redis cache cleared for key: " + idempotencyKey,
                "note", "Database record preserved. Will be re-cached on next request."
        ));
    }

    /**
     * Clear all Redis cache (maintenance operation)
     */
    @Operation(
            summary = "Bütün Redis cache-i sil",
            description = "Sistem sahibi tərəfindən (ADMIN roluna sahib) istifadə olunur. " +
                         "Verilənlər bazasında bütün kayıtlar saxlanılır."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Bütün cache sıfırlandı"),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "403", description = "Admin icazəsi tələb olunur")
    })
    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, String>> clearAllCache() {
        idempotencyService.clearAllRedisCache();
        return ResponseEntity.ok(Map.of(
                "message", "All Redis idempotency cache cleared",
                "note", "All database records preserved. Will be re-cached on next requests.",
                "warning", "This operation clears cache for ALL keys. Use with caution!"
        ));
    }

    /**
     * Get health check for idempotency system
     */
    @Operation(
            summary = "Idempotency sisteminin sağlamlığını yoxlayın",
            description = "Redis və verilənlər bazasının bağlantı statusunu göstərir"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Sistem sağlamdır"),
            @ApiResponse(responseCode = "503", description = "Sistem xətası")
    })
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        try {
            IdempotencyService.IdempotencyStats stats = idempotencyService.getStats();
            boolean isHealthy = stats.getUtilizationPercent() < 95;

            return ResponseEntity.ok(Map.of(
                    "status", isHealthy ? "HEALTHY" : "DEGRADED",
                    "activeKeys", stats.getActiveKeys(),
                    "maxEntries", stats.getMaxEntries(),
                    "utilizationPercent", stats.getUtilizationPercent(),
                    "redisEnabled", stats.isRedisEnabled(),
                    "ttlHours", stats.getTtlHours(),
                    "message", isHealthy ?
                        "Idempotency system is operating normally" :
                        "WARNING: Approaching max entries limit. Consider cleanup or increase limit."
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "ERROR",
                    "message", "Idempotency system error: " + e.getMessage()
            ));
        }
    }
}

