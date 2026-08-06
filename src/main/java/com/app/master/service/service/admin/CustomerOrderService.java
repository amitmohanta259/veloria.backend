package com.app.master.service.service.admin;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.CustomerOrderResponse;
import com.app.master.service.core.response.admin.CustomerStatsResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface CustomerOrderService {

    Page<CustomerOrderResponse> getOrdersByCustomer(String customerId, int page, int pageSize) throws VeloriaException;

    CustomerOrderResponse getOrderByUuid(UUID uuid) throws VeloriaException;

    CustomerStatsResponse getStats(String customerId);
}
