package az.fitnest.payment.controller;

import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.dto.common.PaginatedResponse;
import az.fitnest.payment.service.CoinWalletService;
import az.fitnest.payment.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/coins")
@RequiredArgsConstructor
@Tag(name = "Coin Loyalty", description = "FitNest Coin loyallıq sistemi endpoint-ləri")
@SecurityRequirement(name = "bearerAuth")
public class CoinWalletController {

    private final CoinWalletService coinWalletService;

    @GetMapping("/wallet")
    @Operation(summary = "Coin balans və hesablama məlumatı", description = "İstifadəçinin Coin balansı, AZN ekvivalenti və vaxtı bitməkdə olan coin-lərini qaytarır")
    public ResponseEntity<CoinWalletResponse> getWalletInfo() {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(coinWalletService.getWalletInfo(userId));
    }

    @GetMapping("/history")
    @Operation(summary = "Coin əməliyyat tarixçəsi", description = "İstifadəçinin Coin qazanma/xərcləmə/bonus əməliyyatlarının tarixçəsini səhifələnmiş şəkildə qaytarır")
    public ResponseEntity<PaginatedResponse<CoinTransactionResponse>> getTransactionHistory(@ParameterObject Pageable pageable) {
        Long userId = UserContext.getCurrentUserId();
        Page<CoinTransactionResponse> page = coinWalletService.getTransactionHistory(userId, pageable);
        return ResponseEntity.ok(PaginatedResponse.of(page));
    }

    @PostMapping("/calculate-discount")
    @Operation(summary = "Checkout zamanı Coin endirim hesablama", description = "Ödəniş səhifəsində seçilmiş Coin miqdarına görə 20% limit yoxlaması aparır və net məbləği hesablayır")
    public ResponseEntity<CalculateDiscountResponse> calculateDiscount(@Valid @RequestBody CalculateDiscountRequest request) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(coinWalletService.calculateCheckoutDiscount(userId, request.getOriginalPrice(), request.getCoinsToUse()));
    }

    @PostMapping("/welcome-bonus")
    @Operation(summary = "Welcome Bonus müraciəti", description = "Profil tamamlandıqdan sonra 1 dəfəlik 50 Coin Welcome Bonus verir")
    public ResponseEntity<CoinWalletResponse> awardWelcomeBonus(@RequestBody(required = false) WelcomeBonusRequest request) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(coinWalletService.awardWelcomeBonus(userId, request));
    }
}
