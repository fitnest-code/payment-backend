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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/payments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Ödənişlər (Admin)", description = "Ödəniş məlumatlarını idarə etmək üçün administrativ ucluqlar")
@SecurityRequirement(name = "bearerAuth")
public class PaymentAdminController {

    private final UserPaymentService userPaymentService;

    @Operation(
            summary = "Bütün ödənişləri əldə edin",
            description = "Sistemdəki bütün ödənişləri qaytarır. ADMIN rolu tələb olunur."
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
            @ApiResponse(responseCode = "403", description = "Admin icazəsi tələb olunur")
    })
    @GetMapping
    public ResponseEntity<?> getAllPayments(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        if (page != null || size != null) {
            int pageNum = page != null ? Math.max(0, page - 1) : 0;
            int pageSize = size != null && size > 0 ? size : 10;
            org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(pageNum, pageSize);
            return ResponseEntity.ok(userPaymentService.getAllPaymentsPaginated(pageable));
        }
        return ResponseEntity.ok(userPaymentService.getAllPayments());
    }

    @Operation(
            summary = "Ödənişi ID ilə əldə edin",
            description = "İstənilən ödənişi ID-sinə görə qaytarır. ADMIN rolu tələb olunur."
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
            @ApiResponse(responseCode = "403", description = "Admin icazəsi tələb olunur"),
            @ApiResponse(responseCode = "404", description = "Ödəniş tapılmadı")
    })
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPaymentById(
            @Parameter(description = "Ödənişin ID-si") @PathVariable Long paymentId) {
        return ResponseEntity.ok(userPaymentService.getPaymentByIdAdmin(paymentId));
    }

    @Operation(
            summary = "Ödənişi sifariş ID-si ilə əldə edin",
            description = "İstənilən ödənişi sifariş ID-sinə görə qaytarır. ADMIN rolu tələb olunur."
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
            @ApiResponse(responseCode = "403", description = "Admin icazəsi tələb olunur"),
            @ApiResponse(responseCode = "404", description = "Ödəniş tapılmadı")
    })
    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentResponse> getPaymentByOrderId(
            @Parameter(description = "Sifariş ID-si") @PathVariable String orderId) {
        return ResponseEntity.ok(userPaymentService.getPaymentByOrderIdAdmin(orderId));
    }

    @Operation(
            summary = "Ödənişi tranzaksiya ID-si ilə əldə edin",
            description = "İstənilən ödənişi tranzaksiya ID-sinə görə qaytarır. ADMIN rolu tələb olunur."
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
            @ApiResponse(responseCode = "403", description = "Admin icazəsi tələb olunur"),
            @ApiResponse(responseCode = "404", description = "Ödəniş tapılmadı")
    })
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<PaymentResponse> getPaymentByTransactionId(
            @Parameter(description = "Tranzaksiya ID-si") @PathVariable String transactionId) {
        return ResponseEntity.ok(userPaymentService.getPaymentByTransactionIdAdmin(transactionId));
    }

    @Operation(
            summary = "İstifadəçinin ödənişlərini əldə edin",
            description = "Müəyyən istifadəçinin bütün ödənişlərini qaytarır. ADMIN rolu tələb olunur."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = az.fitnest.payment.dto.admin.AdminUserPaymentHistoryResponse.class))
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "403", description = "Admin icazəsi tələb olunur")
    })
    @GetMapping("/user/{userId}/history")
    public ResponseEntity<List<az.fitnest.payment.dto.admin.AdminUserPaymentHistoryResponse>> getUserPayments(
            @Parameter(description = "İstifadəçi ID-si") @PathVariable Long userId) {
        return ResponseEntity.ok(userPaymentService.getUserPaymentHistoryAdmin(userId));
    }
}
