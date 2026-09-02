package az.fitnest.payment.controller;

import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.dto.common.PaginatedResponse;
import az.fitnest.payment.model.enums.CoinTransactionCategory;
import az.fitnest.payment.service.CoinTermsService;
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
    private final CoinTermsService coinTermsService;

    @GetMapping("/wallet")
    @Operation(summary = "Coin balans və etibarlılıq məlumatı", description = "İstifadəçinin Coin balansı, AZN ekvivalenti, ilk coin qazanıldığı tarix, 365 günlük vahid expiryDate və qalan gün sayını qaytarır")
    public ResponseEntity<CoinWalletResponse> getWalletInfo() {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(coinWalletService.getWalletInfo(userId));
    }

    @GetMapping("/terms")
    @Operation(summary = "Coin qaydaları (HTML)", description = "İstifadəçi dilinə uyğun Coin terms HTML sənədini qaytarır")
    public ResponseEntity<CoinTermsResponse> getTerms(
            @RequestParam(required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        String language = lang != null && !lang.isBlank() ? lang : acceptLanguage;
        return ResponseEntity.ok(coinTermsService.getLocalizedTerms(language));
    }

    @GetMapping("/history")
    @Operation(summary = "Coin əməliyyat tarixçəsi",
            description = "category parametri ilə mobil tabları filterlə: ALL, EARNED (qazanılan), SPENT (istifadə edilən), EXPIRED (bitən)")
    public ResponseEntity<PaginatedResponse<CoinTransactionResponse>> getTransactionHistory(
            @RequestParam(required = false, defaultValue = "ALL") CoinTransactionCategory category,
            @ParameterObject Pageable pageable) {
        Long userId = UserContext.getCurrentUserId();
        Page<CoinTransactionResponse> page = coinWalletService.getTransactionHistory(userId, category, pageable);
        return ResponseEntity.ok(PaginatedResponse.of(page));
    }

    @PostMapping({"/calculate-discount", "/checkout/preview"})
    @Operation(summary = "Checkout Preview və Coin endirim hesablama", description = "Plan məlumatı (plan), mövcud coin balansı və tətbiq olunacaq endirim (coin) və yekun ödəniş məbləğini (finalPaymentAmount) strukturlaşdırılmış şəkildə qaytarır. useCoin: true/false ilə ON/OFF state-lərini təyin edir.")
    public ResponseEntity<CalculateDiscountResponse> calculateDiscount(@Valid @RequestBody CalculateDiscountRequest request) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(coinWalletService.calculateCheckoutDiscount(userId, request));
    }

    @PostMapping("/pay-full")
    @Operation(summary = "100% Coin İlə Ödəniş", description = "Balansda paket qiymətini 100% qarşılayan Coin olduqda kart ödənişinə getmədən birbaşa Coin ilə ödəniş edib abunəliyi aktivləşdirir")
    public ResponseEntity<PayFullWithCoinsResponse> payFullWithCoins(@Valid @RequestBody PayFullWithCoinsRequest request) {
        Long userId = UserContext.getCurrentUserId();
        return ResponseEntity.ok(coinWalletService.payFullWithCoins(userId, request));
    }

    // NOT: POST /api/v1/coins/welcome-bonus endpoint-i LƏĞV EDİLDİ.
    // Welcome Bonus artıq qeydiyyat tamamlananda avtomatik olaraq Kafka üzərindən
    // REGISTRATION_COMPLETED event-i alındıqda PaymentEventListener tərəfindən verilir.
    // Client-dən heç bir müraciət gözlənilmir — fraud prevention.
}
