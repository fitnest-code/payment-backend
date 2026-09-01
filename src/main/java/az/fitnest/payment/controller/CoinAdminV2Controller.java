package az.fitnest.payment.controller;

import az.fitnest.payment.dto.coin.*;
import az.fitnest.payment.service.CoinWalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin/coins")
@RequiredArgsConstructor
@Tag(name = "Coin Loyalty Admin V2", description = "Tier/period earn formula konfiqurasiyası və preview")
@SecurityRequirement(name = "bearerAuth")
public class CoinAdminV2Controller {

    private final CoinWalletService coinWalletService;

    @GetMapping("/settings")
    @Operation(summary = "Coin v2 tənzimləmələri", description = "Earn formula parametrləri, tier və müddət əmsalları")
    public ResponseEntity<CoinSettingsV2Response> getSettingsV2() {
        return ResponseEntity.ok(coinWalletService.getSettingsV2());
    }

    @PutMapping("/settings")
    @Operation(summary = "Coin v2 tənzimləmələrini yeniləmək")
    public ResponseEntity<CoinSettingsV2Response> updateSettingsV2(@Valid @RequestBody CoinSettingsV2Request request) {
        return ResponseEntity.ok(coinWalletService.updateSettingsV2(request));
    }

    @PostMapping("/earn/preview")
    @Operation(summary = "Coin qazanc preview", description = "Paket tier/müddət və ödəniş məbləği üzrə gözlənilən Coin")
    public ResponseEntity<CoinEarnPreviewResponse> previewEarn(@Valid @RequestBody CoinEarnPreviewRequest request) {
        return ResponseEntity.ok(coinWalletService.previewEarn(request));
    }

    @PostMapping("/earn/preview-batch")
    @Operation(summary = "Toplu Coin qazanc preview", description = "Abunəlik paketləri üzrə gözlənilən Coin cədvəli")
    public ResponseEntity<CoinEarnPreviewBatchResponse> previewEarnBatch(
            @Valid @RequestBody CoinEarnPreviewBatchRequest request) {
        return ResponseEntity.ok(coinWalletService.previewEarnBatch(request));
    }
}
