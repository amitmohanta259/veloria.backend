package com.app.master.service.service.admin;

import com.app.master.service.core.dto.SalesOrderRequest;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.SalesOrderResponse;
import com.app.master.service.core.response.admin.SalesStatsResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface SalesOrderService {

    UUID createSalesOrder(SalesOrderRequest request) throws VeloriaException;

    SalesOrderResponse byUuid(UUID uuid) throws VeloriaException;

    Page<SalesOrderResponse> allOrders(String status, Integer month, Integer year, String search, int page, int pageSize) throws VeloriaException;

    SalesStatsResponse getStats() throws VeloriaException;
}
