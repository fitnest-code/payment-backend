package az.fitnest.payment.dto.dashboard;

/**
 * Parallel dashboard income DTO — does not replace {@code IncomeReportResponse}.
 */
public record DashboardIncomeSummaryResponse(
        double totalIncome,
        Double incomeDeltaPct,
        boolean incomePositiveTrend,
        double refundAmount,
        double pendingBnplAmount,
        String dateFrom,
        String dateTo
) {}
