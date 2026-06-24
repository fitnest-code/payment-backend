package az.fitnest.payment.controller;

import az.fitnest.payment.client.SubscriptionPackageGrpcClient;
import az.fitnest.payment.model.entity.Payment;
import az.fitnest.payment.repository.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payment Reports Admin", description = "Ödəniş üzrə hesabat ucluqları")
@SecurityRequirement(name = "bearerAuth")
public class PaymentReportController {

    private final PaymentRepository paymentRepository;
    private final SubscriptionPackageGrpcClient subscriptionPackageGrpcClient;

    private static final String[] AZ_MONTHS = {"Yan", "Fev", "Mar", "Apr", "May", "İyun", "İyul", "Avq", "Sen", "Okt", "Noy", "Dek"};

    public record IncomeTrendPoint(
        String periodLabel,
        Map<String, Double> tierValues
    ) {}

    public record IncomeReportResponse(
        double totalIncome,
        double percentageChange,
        boolean isPositiveTrend,
        List<IncomeTrendPoint> trend
    ) {}

    @Operation(summary = "Ümumi gəlir və gəlir trendini gətir")
    @GetMapping("/income")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<IncomeReportResponse> getIncomeReport(
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {

        LocalDateTime end = endDate != null ? endDate : LocalDateTime.now();
        LocalDateTime start = startDate != null ? startDate : end.minusDays(30);

        // 1. Current period successful payments
        List<Payment> currentPayments = paymentRepository.findByStatusAndCreatedDateBetween("SUCCESS", start, end);
        double currentSum = currentPayments.stream().mapToDouble(p -> p.getAmount() != null ? p.getAmount() : 0.0).sum();

        // 2. Previous period successful payments (to calculate percentage change)
        long daysDiff = ChronoUnit.DAYS.between(start, end);
        if (daysDiff <= 0) daysDiff = 30; // safety fallback
        LocalDateTime prevStart = start.minusDays(daysDiff);
        List<Payment> prevPayments = paymentRepository.findByStatusAndCreatedDateBetween("SUCCESS", prevStart, start);
        double prevSum = prevPayments.stream().mapToDouble(p -> p.getAmount() != null ? p.getAmount() : 0.0).sum();

        double pctChange = 0.0;
        if (prevSum > 0) {
            pctChange = ((currentSum - prevSum) / prevSum) * 100.0;
        } else if (currentSum > 0) {
            pctChange = 100.0;
        }
        boolean positiveTrend = currentSum >= prevSum;

        // 3. Resolve package IDs to names using batch gRPC call
        Set<Long> packageIds = currentPayments.stream()
                .map(this::extractPackageId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, String> packageNamesMap = new HashMap<>();
        if (!packageIds.isEmpty()) {
            try {
                var nameInfos = subscriptionPackageGrpcClient.getPackageNamesByIds(new ArrayList<>(packageIds));
                for (var info : nameInfos) {
                    packageNamesMap.put(info.getPackageId(), info.getName().toLowerCase());
                }
            } catch (Exception ex) {
                log.warn("Failed to resolve package names in payment-backend: {}", ex.getMessage());
            }
        }

        // 4. Generate trend data grouped by month
        Map<String, Map<String, Double>> monthlyGroups = new LinkedHashMap<>();

        // Initialize months in range to preserve chronological order
        LocalDateTime temp = start;
        while (temp.isBefore(end) || temp.getMonth() == end.getMonth() && temp.getYear() == end.getYear()) {
            String label = AZ_MONTHS[temp.getMonthValue() - 1];
            monthlyGroups.putIfAbsent(label, new HashMap<>());
            temp = temp.plusMonths(1);
        }

        for (Payment p : currentPayments) {
            if (p.getCreatedDate() == null) continue;
            String label = AZ_MONTHS[p.getCreatedDate().getMonthValue() - 1];
            Map<String, Double> tierVals = monthlyGroups.computeIfAbsent(label, k -> new HashMap<>());

            Long pkgId = extractPackageId(p);
            String tier = "bronze"; // default fallback
            if (pkgId != null) {
                String resolved = packageNamesMap.get(pkgId);
                if (resolved != null) {
                    if (resolved.contains("silver")) tier = "silver";
                    else if (resolved.contains("gold")) tier = "gold";
                    else if (resolved.contains("platinum")) tier = "platinum";
                }
            }

            double amt = p.getAmount() != null ? p.getAmount() : 0.0;
            tierVals.put(tier, tierVals.getOrDefault(tier, 0.0) + amt);
        }

        List<IncomeTrendPoint> trend = new ArrayList<>();
        for (var entry : monthlyGroups.entrySet()) {
            Map<String, Double> values = entry.getValue();
            // Ensure all keys exist
            values.putIfAbsent("bronze", 0.0);
            values.putIfAbsent("silver", 0.0);
            values.putIfAbsent("gold", 0.0);
            values.putIfAbsent("platinum", 0.0);
            trend.add(new IncomeTrendPoint(entry.getKey(), values));
        }

        return ResponseEntity.ok(new IncomeReportResponse(currentSum, pctChange, positiveTrend, trend));
    }

    private Long extractPackageId(Payment payment) {
        if (payment.getDescription() == null) return null;
        try {
            String desc = payment.getDescription();
            if (desc.contains("packageId:")) {
                String[] parts = desc.split(",");
                for (String part : parts) {
                    if (part.trim().startsWith("packageId:")) {
                        return Long.parseLong(part.trim().replace("packageId:", "").trim());
                    }
                }
            }
        } catch (Exception e) {
            // Ignore parse errors
        }
        return null;
    }
}
