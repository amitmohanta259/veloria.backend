package com.app.master.service.service.admin.impl;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.ReturnReasonResponse;
import com.app.master.service.core.response.admin.ReturnTrendPointResponse;
import com.app.master.service.core.response.admin.ReturnsLogResponse;
import com.app.master.service.core.response.admin.ReturnsStatsResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.CustomerOrderItemRepository;
import com.app.master.service.service.admin.ReturnsService;
import com.google.common.base.Strings;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ReturnsServiceImpl extends AppService implements ReturnsService {

    private final CustomerOrderItemRepository itemRepository;

    public ReturnsServiceImpl(CustomerOrderItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    @Override
    public Page<ReturnsLogResponse> getReturnsLog(Integer month, Integer year, String search,
                                                   int page, int pageSize) throws VeloriaException {
        Pageable pageable = PageRequest.of(page, pageSize);
        String searchParam = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();
        return itemRepository.findReturnsView(month, year, searchParam, pageable)
                .map(this::rowToResponse);
    }

    @Override
    public ReturnsStatsResponse getStats() throws VeloriaException {
        long totalReturns = itemRepository.countReturnedItems();
        long totalOrders  = itemRepository.countAllItems();
        double returnRate = totalOrders == 0 ? 0.0
                : Math.round((totalReturns * 100.0 / totalOrders) * 100.0) / 100.0;
        Long returnValue  = itemRepository.sumReturnedItemValue();
        return ReturnsStatsResponse.builder()
                .totalReturns(totalReturns)
                .totalOrders(totalOrders)
                .returnRate(returnRate)
                .totalReturnValue(returnValue != null ? returnValue : 0L)
                .build();
    }

    @Override
    public List<ReturnTrendPointResponse> getTrends(String period) throws VeloriaException {
        List<Object[]> raw = "monthly".equalsIgnoreCase(period)
                ? itemRepository.findMonthlyTrends()
                : itemRepository.findWeeklyTrends();
        return raw.stream().map(row -> ReturnTrendPointResponse.builder()
                .label((String) row[0])
                .count(row[2] instanceof Long l ? l : ((Number) row[2]).longValue())
                .build()).toList();
    }

    @Override
    public List<ReturnReasonResponse> getReasons() throws VeloriaException {
        List<Object[]> raw = itemRepository.findTopReturnReasons();
        long total = raw.stream()
                .mapToLong(r -> r[1] instanceof Long l ? l : ((Number) r[1]).longValue())
                .sum();
        return raw.stream().map(row -> {
            long count = row[1] instanceof Long l ? l : ((Number) row[1]).longValue();
            double pct = total == 0 ? 0.0 : Math.round(count * 1000.0 / total) / 10.0;
            return ReturnReasonResponse.builder()
                    .reason((String) row[0])
                    .count(count)
                    .percentage(pct)
                    .build();
        }).toList();
    }

    private ReturnsLogResponse rowToResponse(Object[] row) {
        UUID   itemUuid        = (UUID)   row[0];
        String orderCode       = (String) row[1];
        UUID   productUuid     = (UUID)   row[2];
        String productName     = (String) row[3];
        String skuId           = (String) row[4];
        Long   unitPrice       = row[5] instanceof Long l ? l : ((Number) row[5]).longValue();
        String currency        = (String) row[6];
        String customerName    = (String) row[7];
        String customerEmail   = (String) row[8];
        String status          = (String) row[9];
        Instant placedAt       = row[10] instanceof Timestamp ts ? ts.toInstant() : (Instant) row[10];
        String selectedDim     = (String) row[11];
        String reasonForReturn = (String) row[12];

        return ReturnsLogResponse.builder()
                .itemUuid(itemUuid)
                .orderCode(orderCode)
                .productUuid(productUuid)
                .productName(productName)
                .skuId(skuId)
                .unitPrice(unitPrice)
                .currency(currency)
                .customerName(customerName)
                .customerEmail(customerEmail)
                .status(status)
                .selectedDimension(selectedDim)
                .reasonForReturn(reasonForReturn)
                .orderPlacedAt(placedAt)
                .build();
    }
}
