package com.app.master.service.service.admin.impl;

import com.app.master.service.core.entity.SalesOrderEntity;
import com.app.master.service.core.response.admin.DashboardSummaryResponse;
import com.app.master.service.core.response.admin.DashboardSummaryResponse.*;
import com.app.master.service.repository.admin.SalesOrderRepository;
import com.app.master.service.service.admin.DashboardService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardServiceImpl implements DashboardService {

    private final SalesOrderRepository repo;

    public DashboardServiceImpl(SalesOrderRepository repo) {
        this.repo = repo;
    }

    @Override
    public DashboardSummaryResponse getSummary() {
        LocalDate today = LocalDate.now();
        int year  = today.getYear();
        int month = today.getMonthValue();
        int prevYear = year - 1;
        int prevMonth = month == 1 ? 12 : month - 1;
        int prevMonthYear = month == 1 ? prevYear : year;

        // ── Stats ────────────────────────────────────────────────────────────
        long grossRev     = nvl(repo.sumRevenueByYearAndMonth(year, month));
        long prevRev      = nvl(repo.sumRevenueByYearAndMonth(prevMonthYear, prevMonth));
        double revGrowth  = growthPct(grossRev, prevRev);
        long activeOrders = nvl(repo.countActiveOrders());
        double returnRate = nvlD(repo.findReturnRate());
        long totalOrders  = repo.countByArchiveFalse();

        StatsData stats = StatsData.builder()
                .grossRevenue(grossRev)
                .grossRevenueGrowth(revGrowth)
                .activeOrders(activeOrders)
                .returnRate(returnRate)
                .totalOrders(totalOrders)
                .build();

        // ── Monthly chart ────────────────────────────────────────────────────
        List<Object[]> monthlyRaw = repo.findMonthlyChartData(year, prevYear);
        Map<Integer, long[]> monthMap = new LinkedHashMap<>();
        for (Object[] r : monthlyRaw) {
            int yr = ((Number) r[0]).intValue();
            int mo = ((Number) r[1]).intValue();
            long rev = ((Number) r[2]).longValue();
            long ord = ((Number) r[3]).longValue();
            if (yr == year) monthMap.put(mo, new long[]{rev, ord});
        }

        List<ChartPoint> monthlyActual = new ArrayList<>();
        for (int m = 1; m <= month; m++) {
            long[] d = monthMap.getOrDefault(m, new long[]{0L, 0L});
            monthlyActual.add(ChartPoint.builder().period(m).revenue(d[0]).orders(d[1]).build());
        }

        // Forecast: flat average of last 3 available months
        List<Long> recentRevs = monthlyActual.stream()
                .filter(p -> p.getRevenue() > 0)
                .map(ChartPoint::getRevenue)
                .collect(Collectors.toList());
        long forecastRev = recentRevs.isEmpty() ? 0L :
                recentRevs.stream().reduce(0L, Long::sum) / recentRevs.size();

        List<ChartPoint> monthlyForecast = new ArrayList<>();
        for (int m = month + 1; m <= 12; m++) {
            monthlyForecast.add(ChartPoint.builder().period(m).revenue(forecastRev).orders(0L).build());
        }

        // ── Quarterly chart ──────────────────────────────────────────────────
        List<Object[]> quarterlyRaw = repo.findQuarterlyChartData(year, prevYear);
        Map<Integer, long[]> qMap = new LinkedHashMap<>();
        for (Object[] r : quarterlyRaw) {
            int yr = ((Number) r[0]).intValue();
            int q  = ((Number) r[1]).intValue();
            long rev = ((Number) r[2]).longValue();
            long ord = ((Number) r[3]).longValue();
            if (yr == year) qMap.put(q, new long[]{rev, ord});
        }

        int currentQuarter = (month - 1) / 3 + 1;
        List<ChartPoint> quarterlyActual = new ArrayList<>();
        for (int q = 1; q <= currentQuarter; q++) {
            long[] d = qMap.getOrDefault(q, new long[]{0L, 0L});
            quarterlyActual.add(ChartPoint.builder().period(q).revenue(d[0]).orders(d[1]).build());
        }

        long forecastQRev = (long) quarterlyActual.stream()
                .filter(p -> p.getRevenue() > 0)
                .mapToLong(ChartPoint::getRevenue).average().orElse(0);

        List<ChartPoint> quarterlyForecast = new ArrayList<>();
        for (int q = currentQuarter + 1; q <= 4; q++) {
            quarterlyForecast.add(ChartPoint.builder().period(q).revenue((long) forecastQRev).orders(0L).build());
        }

        // ── Category allocation ──────────────────────────────────────────────
        List<Object[]> catRaw = repo.findRevenueByCategory();
        long totalCatRev = catRaw.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
        List<CategorySlice> categoryAllocation = catRaw.stream()
                .map(r -> {
                    long rev = ((Number) r[1]).longValue();
                    double share = totalCatRev > 0 ? Math.round((rev * 100.0 / totalCatRev) * 10.0) / 10.0 : 0.0;
                    return CategorySlice.builder()
                            .category((String) r[0])
                            .revenue(rev)
                            .share(share)
                            .build();
                })
                .toList();

        // ── Recent orders (first 10) ─────────────────────────────────────────
        Page<SalesOrderEntity> recentPage = repo.findRecentOrders(null, PageRequest.of(0, 10));
        List<RecentOrderRow> recentOrders = recentPage.getContent().stream()
                .map(this::toRow)
                .toList();

        return DashboardSummaryResponse.builder()
                .stats(stats)
                .monthlyActual(monthlyActual)
                .monthlyForecast(monthlyForecast)
                .quarterlyActual(quarterlyActual)
                .quarterlyForecast(quarterlyForecast)
                .categoryAllocation(categoryAllocation)
                .recentOrders(recentOrders)
                .build();
    }

    @Override
    public Page<RecentOrderRow> getOrders(String status, int page, int size) {
        String statusParam = (status == null || status.isBlank() || "All".equalsIgnoreCase(status))
                ? null : status;
        return repo.findRecentOrders(statusParam, PageRequest.of(page, size)).map(this::toRow);
    }

    private RecentOrderRow toRow(SalesOrderEntity e) {
        String badgeType = switch (e.getStatus() != null ? e.getStatus() : "") {
            case "DISPATCHED" -> "dark";
            case "ORDER_PLACED", "IN_TRANSIT", "PLACED" -> "outline";
            case "DONE", "DELIVERED", "RETURNED", "CANCELLED" -> "muted";
            default -> "outline";
        };
        String placedAt = e.getOrderPlacedAt() != null
                ? e.getOrderPlacedAt().atZone(ZoneOffset.UTC)
                    .toLocalDate().toString()
                : "—";
        return RecentOrderRow.builder()
                .orderCode(e.getOrderCode())
                .customerName(e.getCustomerName())
                .status(e.getStatus())
                .totalValue(e.getTotalValue() != null ? e.getTotalValue() : 0L)
                .placedAt(placedAt)
                .badgeType(badgeType)
                .build();
    }

    private long nvl(Long v) { return v != null ? v : 0L; }
    private double nvlD(Double v) { return v != null ? v : 0.0; }

    private double growthPct(long current, long prev) {
        if (prev == 0) return current > 0 ? 100.0 : 0.0;
        return Math.round(((current - prev) * 100.0 / prev) * 10.0) / 10.0;
    }
}
