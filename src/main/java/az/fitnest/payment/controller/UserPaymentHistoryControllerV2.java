package az.fitnest.payment.controller;

import az.fitnest.payment.dto.common.PaymentOrderStatusV2Response;
import az.fitnest.payment.service.UserPaymentService;
import az.fitnest.payment.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * v2 payment-history surface. Keeps poll payloads small (status + transactionId)
 * so clients can chain into history/{transactionId} for the receipt without a second guess.
 */
@RestController
@RequestMapping("/api/v2/me/payments")
@RequiredArgsConstructor
@Tag(name = "Ödəniş Tarixi V2", description = "İstifadəçi ödəniş statusu (v2)")
@SecurityRequirement(name = "bearerAuth")
public class UserPaymentHistoryControllerV2 {

    private final UserPaymentService userPaymentService;

    @Operation(
            summary = "Ödəniş statusu (orderId) — v2",
            description = """
                    orderId ilə status və transactionId qaytarır.
                    Qəbz: GET /api/v1/me/payments/history/{transactionId}
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Status uğurla qaytarıldı",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PaymentOrderStatusV2Response.class)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur", content = @Content),
            @ApiResponse(responseCode = "404", description = "Ödəniş tapılmadı", content = @Content)
    })
    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentOrderStatusV2Response> getPaymentStatusByOrderId(
            @Parameter(hidden = true) @AuthenticationPrincipal Principal user,
            @PathVariable String orderId
    ) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(userPaymentService.getPaymentOrderStatusByOrderId(orderId, userId));
    }
}
