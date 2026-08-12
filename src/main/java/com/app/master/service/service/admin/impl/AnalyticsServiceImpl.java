package com.app.master.service.service.admin.impl;

import com.app.master.service.core.response.admin.AnalyticsSummaryResponse;
import com.app.master.service.core.response.admin.AnalyticsSummaryResponse.*;
import com.app.master.service.repository.admin.SalesOrderRepository;
import com.app.master.service.service.admin.AnalyticsService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AnalyticsServiceImpl implements AnalyticsService {

    private final SalesOrderRepository salesOrderRepository;

    public AnalyticsServiceImpl(SalesOrderRepository salesOrderRepository) {
        this.salesOrderRepository = salesOrderRepository;
    }

    @Override
    public AnalyticsSummaryResponse getSummary() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        int month = today.getMonthValue();
        int quarter = (month - 1) / 3 + 1;

        int prevYear = year - 1;
        int prevQuarter = quarter == 1 ? 4 : quarter - 1;
        int prevQuarterYear = quarter == 1 ? prevYear : year;
        int prevMonth = month == 1 ? 12 : month - 1;
        int prevMonthYear = month == 1 ? prevYear : year;

        // ── Metrics ──────────────────────────────────────────────────────────
        long ytdRev = nvl(salesOrderRepository.sumRevenueByYear(year));
        long ytdOrders = nvl(salesOrderRepository.countOrdersByYear(year));
        long prevYtdRev = nvl(salesOrderRepository.sumRevenueByYear(prevYear));

        long qtdRev = nvl(salesOrderRepository.sumRevenueByYearAndQuarter(year, quarter));
        long qtdOrders = nvl(salesOrderRepository.countOrdersByYearAndQuarter(year, quarter));
        long prevQtdRev = nvl(salesOrderRepository.sumRevenueByYearAndQuarter(prevQuarterYear, prevQuarter));

        long monthRev = nvl(salesOrderRepository.sumRevenueByYearAndMonth(year, month));
        long monthOrders = nvl(salesOrderRepository.countOrdersByYearAndMonth(year, month));
        long prevMonthRev = nvl(salesOrderRepository.sumRevenueByYearAndMonth(prevMonthYear, prevMonth));

        MetricsData metrics = MetricsData.builder()
                .ytdRevenue(ytdRev)
                .ytdOrders(ytdOrders)
                .ytdGrowth(growthPct(ytdRev, prevYtdRev))
                .qtdRevenue(qtdRev)
                .qtdOrders(qtdOrders)
                .qtdGrowth(growthPct(qtdRev, prevQtdRev))
                .monthRevenue(monthRev)
                .monthOrders(monthOrders)
                .monthGrowth(growthPct(monthRev, prevMonthRev))
                .build();

        // ── Chart data ───────────────────────────────────────────────────────
        List<Object[]> monthlyRaw = salesOrderRepository.findMonthlyChartData(year, prevYear);
        List<RevenuePoint> monthlyChart = monthlyRaw.stream()
                .map(r -> RevenuePoint.builder()
                        .year(((Number) r[0]).intValue())
                        .period(((Number) r[1]).intValue())
                        .revenue(((Number) r[2]).longValue())
                        .orders(((Number) r[3]).longValue())
                        .build())
                .toList();

        List<Object[]> quarterlyRaw = salesOrderRepository.findQuarterlyChartData(year, prevYear);
        List<RevenuePoint> quarterlyChart = quarterlyRaw.stream()
                .map(r -> RevenuePoint.builder()
                        .year(((Number) r[0]).intValue())
                        .period(((Number) r[1]).intValue())
                        .revenue(((Number) r[2]).longValue())
                        .orders(((Number) r[3]).longValue())
                        .build())
                .toList();

        // ── Category breakdown ───────────────────────────────────────────────
        List<Object[]> catRaw = salesOrderRepository.findRevenueByCategory();
        long totalCatRev = catRaw.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
        List<CategoryRevenue> categories = catRaw.stream()
                .map(r -> {
                    long rev = ((Number) r[1]).longValue();
                    double share = totalCatRev > 0 ? (rev * 100.0 / totalCatRev) : 0.0;
                    return CategoryRevenue.builder()
                            .category((String) r[0])
                            .revenue(rev)
                            .share(Math.round(share * 10.0) / 10.0)
                            .build();
                })
                .toList();

        // ── Monthly summary table (with growth) ──────────────────────────────
        List<Object[]> summaryRaw = salesOrderRepository.findMonthlySummary();
        List<MonthlySummaryRow> monthlySummary = new ArrayList<>();
        for (int i = 0; i < summaryRaw.size(); i++) {
            Object[] r = summaryRaw.get(i);
            int rowYear = ((Number) r[0]).intValue();
            int rowMonth = ((Number) r[1]).intValue();
            long rowRev = ((Number) r[2]).longValue();
            long rowOrders = ((Number) r[3]).longValue();

            // Growth = compare to next item (which is prev month since sorted DESC)
            Double growth = null;
            if (i + 1 < summaryRaw.size()) {
                long prevRev = ((Number) summaryRaw.get(i + 1)[2]).longValue();
                growth = growthPct(rowRev, prevRev);
            }

            String periodLabel = Month.of(rowMonth).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + rowYear;
            monthlySummary.add(MonthlySummaryRow.builder()
                    .period(periodLabel)
                    .year(rowYear)
                    .month(rowMonth)
                    .revenue(rowRev)
                    .orders(rowOrders)
                    .growthPct(growth)
                    .build());
        }

        // ── Available years ──────────────────────────────────────────────────
        List<Integer> availableYears = salesOrderRepository.findDistinctOrderYears();

        return AnalyticsSummaryResponse.builder()
                .metrics(metrics)
                .monthlyChart(monthlyChart)
                .quarterlyChart(quarterlyChart)
                .categories(categories)
                .monthlySummary(monthlySummary)
                .availableYears(availableYears)
                .build();
    }

    private long nvl(Long value) {
        return value != null ? value : 0L;
    }

    private double growthPct(long current, long previous) {
        if (previous == 0) return current > 0 ? 100.0 : 0.0;
        return Math.round(((current - previous) * 100.0 / previous) * 10.0) / 10.0;
    }
}
