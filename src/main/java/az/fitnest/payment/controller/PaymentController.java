package az.fitnest.payment.controller;

import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.service.UserPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Ödənişlər", description = "Ödəniş məlumatlarını əldə etmək üçün ucluqlar")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final UserPaymentService userPaymentService;

    @Operation(
            summary = "İstifadəçinin ödənişlərini əldə edin",
            description = "Autentifikasiya olunmuş istifadəçinin bütün ödənişlərini qaytarır"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = PaymentResponse.class))
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur")
    })
    @GetMapping("/me")
    public ResponseEntity<List<PaymentResponse>> getMyPayments(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(userPaymentService.getUserPayments(userId));
    }

    @Operation(
            summary = "Ödənişi ID ilə əldə edin",
            description = "Ödəniş ID-sinə görə ödəniş məlumatını qaytarır"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "404", description = "Ödəniş tapılmadı")
    })
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPaymentById(
            @Parameter(description = "Ödənişin ID-si") @PathVariable Long paymentId) {
        return ResponseEntity.ok(userPaymentService.getPaymentById(paymentId));
    }

    @Operation(
            summary = "Ödənişi sifariş ID-si ilə əldə edin",
            description = "Sifariş ID-sinə görə ödəniş məlumatını qaytarır"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "404", description = "Ödəniş tapılmadı")
    })
    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentResponse> getPaymentByOrderId(
            @Parameter(description = "Sifariş ID-si") @PathVariable String orderId) {
        return ResponseEntity.ok(userPaymentService.getPaymentByOrderId(orderId));
    }

    @Operation(
            summary = "Ödənişi tranzaksiya ID-si ilə əldə edin",
            description = "Tranzaksiya ID-sinə görə ödəniş məlumatını qaytarır"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "404", description = "Ödəniş tapılmadı")
    })
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<PaymentResponse> getPaymentByTransactionId(
            @Parameter(description = "Tranzaksiya ID-si") @PathVariable String transactionId) {
        return ResponseEntity.ok(userPaymentService.getPaymentByTransactionId(transactionId));
    }

    @Operation(
            summary = "Bütün ödənişləri əldə edin (Admin)",
            description = "Sistemdəki bütün ödənişləri qaytarır (yalnız admin üçün)"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = PaymentResponse.class))
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "403", description = "Yalnız admin icazəsi var")
    })
    @GetMapping("/all")
    public ResponseEntity<List<PaymentResponse>> getAllPayments() {
        // Note: In production, add role-based authorization check here
        // e.g., @PreAuthorize("hasRole('ADMIN')")
        return ResponseEntity.ok(userPaymentService.getAllPayments());
    }
}

