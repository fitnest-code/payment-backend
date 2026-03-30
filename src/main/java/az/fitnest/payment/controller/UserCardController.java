package az.fitnest.payment.controller;

import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.dto.common.SetDefaultCardRequest;
import az.fitnest.payment.dto.common.UserCardResponse;
import az.fitnest.payment.dto.common.DeleteCardRequest;
import az.fitnest.payment.service.UserPaymentService;
import az.fitnest.payment.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/me/cards")
@RequiredArgsConstructor
@Tag(name = "Yadda saxlanmış kartlar", description = "İstifadəçinin yadda saxlanmış kartlarını idarə etmək üçün ucluqlar")
@SecurityRequirement(name = "bearerAuth")
public class UserCardController {

    private final UserPaymentService userPaymentService;

    @Operation(
            summary = "Kartı silin",
            description = "Yadda saxlanmış kartı sistemdən silir"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Kart uğurla silindi")
    })
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCard(
            @Valid @RequestBody DeleteCardRequest request,
            @AuthenticationPrincipal Principal user) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new RuntimeException("Unauthorized: User ID not found");
        }
        userPaymentService.deleteCard(userId, request.cardId());
    }

    @Operation(
            summary = "Bütün yadda saxlanmış kartları əldə edin",
            description = "İstifadəçinin yadda saxlanmış bütün kartlarını qaytarır"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserCardResponse.class))
                    )
            )
    })
    @GetMapping
    public ResponseEntity<List<UserCardResponse>> getAllCards(@AuthenticationPrincipal Principal user) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            List<UserCardResponse> cards = userPaymentService.getUserCards(userId);
            return ResponseEntity.ok(cards);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }
}
