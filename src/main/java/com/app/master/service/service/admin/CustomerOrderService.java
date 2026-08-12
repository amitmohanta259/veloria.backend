package com.app.master.service.service.admin;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.CustomerDetailResponse;
import com.app.master.service.core.response.admin.CustomerOrderResponse;
import com.app.master.service.core.response.admin.CustomerStatsResponse;
import com.app.master.service.core.response.admin.CustomerSummaryResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface CustomerOrderService {

    Page<CustomerOrderResponse> getOrdersByCustomer(String customerId, int page, int pageSize) throws VeloriaException;

    CustomerOrderResponse getOrderByUuid(UUID uuid) throws VeloriaException;

    CustomerStatsResponse getStats(String customerId);

    Page<CustomerSummaryResponse> getCustomerList(String search, String status, int page, int pageSize);

    CustomerDetailResponse getCustomerDetail(String customerId);
}
