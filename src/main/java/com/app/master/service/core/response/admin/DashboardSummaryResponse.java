package com.app.master.service.core.response.admin;

import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSummaryResponse {

    private StatsData stats;
    private List<ChartPoint> monthlyActual;
    private List<ChartPoint> monthlyForecast;
    private List<ChartPoint> quarterlyActual;
    private List<ChartPoint> quarterlyForecast;
    private List<CategorySlice> categoryAllocation;
    private List<RecentOrderRow> recentOrders;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class StatsData {
        private long grossRevenue;
        private double grossRevenueGrowth;
        private long activeOrders;
        private double returnRate;
        private long totalOrders;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ChartPoint {
        private int period;
        private long revenue;
        private long orders;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CategorySlice {
        private String category;
        private long revenue;
        private double share;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecentOrderRow {
        private String orderCode;
        private String customerName;
        private String status;
        private long totalValue;
        private String placedAt;
        private String badgeType;
    }
}
