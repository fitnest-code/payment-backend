package az.fitnest.payment.service.dashboard;

import az.fitnest.payment.dto.dashboard.DashboardIncomeSummaryResponse;
import az.fitnest.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardIncomeServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private DashboardIncomeService service;

    @Test
    void incomeSummary_computesDeltaAndBnpl() {
        when(paymentRepository.sumIncomeExcludingFitCoin(eq("SUCCESS"), any(), any()))
                .thenReturn(200.0, 100.0);
        when(paymentRepository.sumAmountByStatusAndCreatedDateBetween(eq("REFUNDED"), any(), any()))
                .thenReturn(10.0);
        when(paymentRepository.sumAmountByProvidersAndCreatedDateBetween(any(Collection.class), any(), any()))
                .thenReturn(40.0);

        DashboardIncomeSummaryResponse res = service.getIncomeSummary(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

        assertEquals(200.0, res.totalIncome());
        assertEquals(100.0, res.incomeDeltaPct());
        assertTrue(res.incomePositiveTrend());
        assertEquals(10.0, res.refundAmount());
        assertEquals(40.0, res.pendingBnplAmount());
        assertEquals("2026-11-01", res.dateFrom());
        assertEquals("2026-11-30", res.dateTo());
    }

    @Test
    void incomeSummary_nullDeltaWhenPreviousZero() {
        when(paymentRepository.sumIncomeExcludingFitCoin(eq("SUCCESS"), any(), any()))
                .thenReturn(50.0, 0.0);
        when(paymentRepository.sumAmountByStatusAndCreatedDateBetween(eq("REFUNDED"), any(), any()))
                .thenReturn(0.0);
        when(paymentRepository.sumAmountByProvidersAndCreatedDateBetween(any(Collection.class), any(), any()))
                .thenReturn(0.0);

        DashboardIncomeSummaryResponse res = service.getIncomeSummary(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));

        assertNull(res.incomeDeltaPct());
        assertTrue(res.incomePositiveTrend());
    }
}
