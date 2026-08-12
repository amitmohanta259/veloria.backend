package com.app.master.service.service.admin;

import com.app.master.service.core.response.admin.DashboardSummaryResponse;
import org.springframework.data.domain.Page;

public interface DashboardService {
    DashboardSummaryResponse getSummary();
    Page<DashboardSummaryResponse.RecentOrderRow> getOrders(String status, int page, int size);
}
