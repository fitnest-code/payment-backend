package az.fitnest.payment.controller;

import az.fitnest.payment.dto.common.PaginatedResponse;
import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.service.UserPaymentService;
import az.fitnest.payment.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/me/payments")
@RequiredArgsConstructor
@Tag(
        name = "Ödəniş Tarixi",
        description = "Authenticated istifadəçinin ödəniş tarixçəsini səhifələnmiş (paginated) şəkildə əldə etmək üçün endpointlər"
)
@SecurityRequirement(name = "bearerAuth")
public class UserPaymentHistoryController {

    private final UserPaymentService userPaymentService;

    @Operation(
            summary = "İstifadəçinin ödəniş tarixçəsi",
            description = """
            Bu endpoint autentifikasiya olunmuş istifadəçinin ödəniş tarixçəsini qaytarır.

            Xüsusiyyətlər:
            - Səhifələmə (pagination) dəstəklənir
            - Ay üzrə filtrasiya mümkündür (fromMonth)
            - Nəticələr azalan tarix sırası ilə qaytarıla bilər (servisdən asılı olaraq)

            Qeyd:
            - `page` 1-dən başlayır (backend-də 0-based-ə çevrilir)
            - `size` çox böyük verildikdə performans problemi yarada bilər
        """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Ödəniş tarixçəsi uğurla qaytarıldı",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PaginatedResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "İstifadəçi autentifikasiya olunmayıb və ya token etibarsızdır",
                    content = @Content
            )
    })
    @GetMapping("/history")
    public ResponseEntity<PaginatedResponse<PaymentResponse>> getUserPaymentHistory(

            @Parameter(
                    hidden = true,
                    description = "Spring Security tərəfindən inject olunan istifadəçi məlumatı"
            )
            @AuthenticationPrincipal Principal user,

            @Parameter(
                    name = "page",
                    in = ParameterIn.QUERY,
                    description = "Səhifə nömrəsi (1-dən başlayır)",
                    example = "1",
                    schema = @Schema(
                            type = "integer",
                            defaultValue = "1",
                            minimum = "1"
                    )
            )
            @RequestParam(defaultValue = "1") int page,

            @Parameter(
                    name = "size",
                    in = ParameterIn.QUERY,
                    description = "Bir səhifədə qaytarılacaq maksimum ödəniş sayı",
                    example = "20",
                    schema = @Schema(
                            type = "integer",
                            defaultValue = "20",
                            minimum = "1",
                            maximum = "100"
                    )
            )
            @RequestParam(defaultValue = "20") int size,

            @Parameter(
                    name = "fromMonth",
                    in = ParameterIn.QUERY,
                    description = """
                Filtrləmə üçün başlanğıc ay (1-12 arası)

                Nümunə:
                - 1 = Yanvar
                - 12 = Dekabr

                Əgər verilməzsə, bütün tarixçə qaytarılır
            """,
                    example = "3",
                    schema = @Schema(
                            type = "integer",
                            minimum = "1",
                            maximum = "12",
                            nullable = true
                    )
            )
            @RequestParam(required = false) Integer fromMonth
    ) {

        Long userId = UserContext.getCurrentUserId();

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);

        PaginatedResponse<PaymentResponse> response =
                userPaymentService.getUserPaymentHistory(userId, pageable, fromMonth);

        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Xüsusi ödənişin detalları",
            description = "Bu endpoint autentifikasiya olunmuş istifadəçinin transactionId-ə əsasən konkret ödənişin detallarını qaytarır."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Ödəniş detalları uğurla qaytarıldı",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PaymentResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "İstifadəçi autentifikasiya olunmayıb və ya token etibarsızdır",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Ödəniş tapılmadı",
                    content = @Content
            )
    })
    @GetMapping("/history/{transactionId}")
    public ResponseEntity<PaymentResponse> getUserPaymentHistoryByTransactionId(
            @Parameter(
                    hidden = true,
                    description = "Spring Security tərəfindən inject olunan istifadəçi məlumatı"
            )
            @AuthenticationPrincipal Principal user,
            
            @Parameter(
                    description = "Ödənişin transaction identifikatoru"
            )
            @PathVariable String transactionId
    ) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        PaymentResponse response = userPaymentService.getPaymentByTransactionId(transactionId, userId);
        return ResponseEntity.ok(response);
    }
}
