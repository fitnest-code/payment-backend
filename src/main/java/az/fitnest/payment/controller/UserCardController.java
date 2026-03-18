package az.fitnest.payment.controller;

import az.fitnest.payment.dto.common.PaymentResponse;
import az.fitnest.payment.dto.common.SetDefaultCardRequest;
import az.fitnest.payment.dto.common.UserCardResponse;
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
            summary = "Varsayılan kartı əldə edin",
            description = "İstifadəçinin varsayılan kartını qaytarır"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UserCardResponse.class)
                    )
            )
    })
    @GetMapping("/default")
    public ResponseEntity<UserCardResponse> getDefaultCard(@AuthenticationPrincipal Principal user) {
        Long userId = Long.parseLong(user.getName());
        return ResponseEntity.ok(userPaymentService.getDefaultCard(userId));
    }

    @Operation(
            summary = "Varsayılan kartı dəyişdirin",
            description = "Göstərilən kartı istifadəçinin varsayılan kartı kimi təyin edir"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Kart uğurla varsayılan kart kimi təyin edildi",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UserCardResponse.class)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Yanlış sorğu")
    })
    @PutMapping("/default")
    public ResponseEntity<UserCardResponse> setDefaultCard(
            @Valid @RequestBody SetDefaultCardRequest request,
            @AuthenticationPrincipal Principal user) {
        Long userId = Long.parseLong(user.getName());
        return ResponseEntity.ok(userPaymentService.setDefaultCard(userId, request.cardId()));
    }

    @Operation(
            summary = "Kartı silin",
            description = "Yadda saxlanmış kartı sistemdən silir"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Kart uğurla silindi")
    })
    @DeleteMapping("/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCard(
            @Parameter(description = "Silinəcək kartın ID-si") @PathVariable Long cardId,
            @AuthenticationPrincipal Principal user) {
        Long userId = Long.parseLong(user.getName());
        userPaymentService.deleteCard(userId, cardId);
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
        Long userId = Long.parseLong(user.getName());
        List<UserCardResponse> cards = userPaymentService.getUserCards(userId);
        return ResponseEntity.ok(cards);
    }
}