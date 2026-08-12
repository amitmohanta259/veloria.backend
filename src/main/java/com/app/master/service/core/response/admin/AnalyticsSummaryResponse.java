package com.app.master.service.core.response.admin;

import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsSummaryResponse {

    private MetricsData metrics;
    private List<RevenuePoint> monthlyChart;
    private List<RevenuePoint> quarterlyChart;
    private List<CategoryRevenue> categories;
    private List<MonthlySummaryRow> monthlySummary;
    private List<Integer> availableYears;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricsData {
        private long ytdRevenue;
        private long ytdOrders;
        private double ytdGrowth;
        private long qtdRevenue;
        private long qtdOrders;
        private double qtdGrowth;
        private long monthRevenue;
        private long monthOrders;
        private double monthGrowth;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevenuePoint {
        private int year;
        private int period;
        private long revenue;
        private long orders;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryRevenue {
        private String category;
        private long revenue;
        private double share;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlySummaryRow {
        private String period;
        private int year;
        private int month;
        private long revenue;
        private long orders;
        private Double growthPct;
    }
}
