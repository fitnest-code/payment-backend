package az.fitnest.payment.service.dashboard;

import az.fitnest.payment.dto.dashboard.DashboardIncomeSummaryResponse;
import az.fitnest.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardIncomeService {

    private static final ZoneId BAKU = ZoneId.of("Asia/Baku");
    private static final List<String> BNPL_PROVIDERS = List.of("ABB_BNPL", "KAPITAL_BOL_ODE");

    private final PaymentRepository paymentRepository;

    public DashboardIncomeSummaryResponse getIncomeSummary(LocalDate dateFrom, LocalDate dateTo) {
        LocalDate[] range = resolveRange(dateFrom, dateTo);
        LocalDate from = range[0];
        LocalDate to = range[1];

        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.atTime(LocalTime.of(23, 59, 59));

        long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1);
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);
        LocalDateTime prevStart = prevFrom.atStartOfDay();
        LocalDateTime prevEnd = prevTo.atTime(LocalTime.of(23, 59, 59));

        double current = nz(paymentRepository.sumIncomeExcludingFitCoin("SUCCESS", start, end));
        double previous = nz(paymentRepository.sumIncomeExcludingFitCoin("SUCCESS", prevStart, prevEnd));
        double refunds = nz(paymentRepository.sumAmountByStatusAndCreatedDateBetween("REFUNDED", start, end));
        double bnpl = nz(paymentRepository.sumAmountByProvidersAndCreatedDateBetween(BNPL_PROVIDERS, start, end));

        Double deltaPct = null;
        if (previous > 0) {
            deltaPct = ((current - previous) / previous) * 100.0;
        }

        return new DashboardIncomeSummaryResponse(
                current,
                deltaPct,
                current >= previous,
                refunds,
                bnpl,
                from.toString(),
                to.toString()
        );
    }

    private static LocalDate[] resolveRange(LocalDate dateFrom, LocalDate dateTo) {
        LocalDate today = LocalDate.now(BAKU);
        LocalDate to = dateTo != null ? dateTo : today;
        LocalDate from = dateFrom != null ? dateFrom : to.minusDays(29);
        if (from.isAfter(to)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }
        return new LocalDate[]{from, to};
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }
}
