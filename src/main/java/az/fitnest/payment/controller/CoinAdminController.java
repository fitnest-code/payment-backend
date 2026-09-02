package az.fitnest.payment.controller;

import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.dto.common.PaginatedResponse;
import az.fitnest.payment.service.CoinTermsService;
import az.fitnest.payment.service.CoinWalletService;
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
@RequestMapping("/api/v1/admin/coins")
@RequiredArgsConstructor
@Tag(name = "Coin Loyalty Admin", description = "Admin tərəfi Coin konfiqurasiyaları və monitorinq")
@SecurityRequirement(name = "bearerAuth")
public class CoinAdminController {

    private final CoinWalletService coinWalletService;
    private final CoinTermsService coinTermsService;

    @GetMapping("/settings")
    @Operation(summary = "Coin sistem tənzimləmələri", description = "Cari Welcome bonus miqdarı, konvertasiya dərəcəsi, max endirim % və etibarlılıq müddətini qaytarır")
    public ResponseEntity<CoinSettingsResponse> getSettings() {
        return ResponseEntity.ok(coinWalletService.getSettings());
    }

    @PutMapping("/settings")
    @Operation(summary = "Coin tənzimləmələrini yeniləmək", description = "Coin sisteminin parametr konfiqurasiyalarını yeniləyir")
    public ResponseEntity<CoinSettingsResponse> updateSettings(@Valid @RequestBody CoinSettingsRequest request) {
        return ResponseEntity.ok(coinWalletService.updateSettings(request));
    }

    @GetMapping("/terms")
    @Operation(summary = "Coin qaydaları (HTML)", description = "Admin üçün Coin terms HTML sənədini AZ/EN/RU dillərində qaytarır")
    public ResponseEntity<CoinTermsAdminResponse> getTerms() {
        return ResponseEntity.ok(coinTermsService.getAdminTerms());
    }

    @PutMapping("/terms")
    @Operation(summary = "Coin qaydalarını saxla", description = "Singleton Coin terms HTML sənədini AZ/EN/RU dillərində upsert edir")
    public ResponseEntity<CoinTermsAdminResponse> saveTerms(@Valid @RequestBody CoinTermsAdminRequest request) {
        return ResponseEntity.ok(coinTermsService.saveAdminTerms(request));
    }

    @DeleteMapping("/terms")
    @Operation(summary = "Coin qaydalarını sil", description = "Coin terms HTML sənədini silir")
    public ResponseEntity<Void> deleteTerms() {
        coinTermsService.deleteTerms();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/adjust")
    @Operation(summary = "Manual Coin korreksiyası (Credit / Debit)", description = "Admin tərəfindən istifadəçi balansına manual Coin əlavə etmək (müsbət) və ya çıxarmaq (mənfi)")
    public ResponseEntity<CoinWalletResponse> adjustCoins(@Valid @RequestBody ManualCoinAdjustRequest request) {
        return ResponseEntity.ok(coinWalletService.manualAdjustCoins(request));
    }

    @PostMapping("/bulk-adjust")
    @Operation(summary = "Toplu (Bulk) Coin artırılması / azaldılması", description = "Seçilmiş istifadəçi siyahısına toplu şəkildə Coin əlavə edilməsi və bildiriş göndərilməsi")
    public ResponseEntity<BulkCoinAdjustResponse> bulkAdjustCoins(@Valid @RequestBody BulkCoinAdjustRequest request) {
        return ResponseEntity.ok(coinWalletService.bulkAdjustCoins(request));
    }

    @PostMapping("/bulk-welcome-bonus")
    @Operation(summary = "Mövcud userlər üçün xoş gəldin bonusu", description = "isWelcomeBonusReceived=false olan bütün istifadəçilərə admin ayarlarındakı qeydiyyat bonusunu verir")
    public ResponseEntity<BulkCoinAdjustResponse> bulkWelcomeBonus(@Valid @RequestBody BulkWelcomeBonusRequest request) {
        return ResponseEntity.ok(coinWalletService.bulkWelcomeBonus(request));
    }

    @GetMapping("/history")
    @Operation(summary = "Bütün Coin əməliyyat tarixçəsi", description = "Monitorinq üçün sistemdəki bütün istifadəçi Coin tranzaksiyalarının siyahısı")
    public ResponseEntity<PaginatedResponse<CoinTransactionResponse>> getAllTransactions(@ParameterObject Pageable pageable) {
        Page<CoinTransactionResponse> page = coinWalletService.getAllTransactionsForAdmin(pageable);
        return ResponseEntity.ok(PaginatedResponse.of(page));
    }
}
