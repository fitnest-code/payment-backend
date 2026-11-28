package az.fitnest.payment.controller;

import az.fitnest.payment.dto.dashboard.DashboardIncomeSummaryResponse;
import az.fitnest.payment.service.dashboard.DashboardIncomeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Parallel dashboard income API. Leaves {@link PaymentReportController} unchanged.
 */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard Income", description = "Admin dashboard income aggregates (non-breaking parallel API)")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'OWNER')")
public class DashboardIncomeController {

    private final DashboardIncomeService dashboardIncomeService;

    @Operation(summary = "Dashboard income summary (excludes FitCoin; includes BNPL volume)")
    @GetMapping("/income-summary")
    public ResponseEntity<DashboardIncomeSummaryResponse> getIncomeSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return ResponseEntity.ok(dashboardIncomeService.getIncomeSummary(dateFrom, dateTo));
    }
}
