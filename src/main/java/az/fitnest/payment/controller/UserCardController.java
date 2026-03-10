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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me/cards")
@RequiredArgsConstructor
@Tag(name = "Yadda saxlanmış kartlar", description = "İstifadəçinin yadda saxlanmış kartlarını idarə etmək üçün ucluqlar")
@SecurityRequirement(name = "bearerAuth")
public class UserCardController {

    private final UserPaymentService userPaymentService;

    @Operation(
            summary = "Yadda saxlanmış kartları əldə edin",
            description = "Autentifikasiya olunmuş istifadəçinin bütün yadda saxlanmış kartlarını qaytarır"
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Uğurlu cavab",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserCardResponse.class))
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur")
    })
    @GetMapping(value = {"", "/card/list"})
    public ResponseEntity<List<UserCardResponse>> getUserCards(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(userPaymentService.getUserCards(userId));
    }

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
            ),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur")
    })
    @GetMapping("/default")
    public ResponseEntity<UserCardResponse> getDefaultCard(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        UserCardResponse defaultCard = userPaymentService.getDefaultCard(userId);
        if (defaultCard == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(defaultCard);
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
            @ApiResponse(responseCode = "400", description = "Yanlış sorğu"),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "404", description = "Kart tapılmadı")
    })
    @PutMapping("/default")
    public ResponseEntity<UserCardResponse> setDefaultCard(
            @Valid @RequestBody SetDefaultCardRequest request,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(userPaymentService.setDefaultCard(userId, request.cardId()));
    }

    @Operation(
            summary = "Kartı silin",
            description = "Yadda saxlanmış kartı sistemdən silir"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Kart uğurla silindi"),
            @ApiResponse(responseCode = "401", description = "Autentifikasiya tələb olunur"),
            @ApiResponse(responseCode = "404", description = "Kart tapılmadı")
    })
    @DeleteMapping("/{cardId}")
    public ResponseEntity<Void> deleteCard(
            @Parameter(description = "Silinəcək kartın ID-si") @PathVariable Long cardId,
            Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        userPaymentService.deleteCard(userId, cardId);
        return ResponseEntity.noContent().build();
    }
}
