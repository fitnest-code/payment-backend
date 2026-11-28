package az.fitnest.payment.dto.dashboard;

import java.util.List;

/**
 * Parallel dashboard income DTO — does not replace {@code IncomeReportResponse}.
 */
public record DashboardIncomeSummaryResponse(
        double totalIncome,
        Double incomeDeltaPct,
        boolean incomePositiveTrend,
        double refundAmount,
        double pendingBnplAmount,
        List<Double> sparkline,
        String dateFrom,
        String dateTo
) {}
