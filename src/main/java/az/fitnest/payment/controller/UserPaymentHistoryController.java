package az.fitnest.payment.controller;

import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.service.UserPaymentService;
import az.fitnest.payment.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/me/payments")
@RequiredArgsConstructor
@Tag(name = "Ödəniş Tarixi", description = "İstifadəçinin ödəniş tarixçəsini görmək üçün ucluqlar")
@SecurityRequirement(name = "bearerAuth")
public class UserPaymentHistoryController {
    private final UserPaymentService userPaymentService;

    @Operation(
        summary = "İstifadəçi ödəniş tarixçəsi",
        description = "İstifadəçi öz ödəniş tarixçəsini görmək üçün endpoint"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Uğurlu cavab")
    })
    @GetMapping
    public ResponseEntity<List<PaymentResponse>> getUserPaymentHistory(@AuthenticationPrincipal Principal user) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<PaymentResponse> payments = userPaymentService.getUserPayments(userId);
        return ResponseEntity.ok(payments);
    }
}

