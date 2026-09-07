package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.SalesOrderRequest;
import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.entity.InventoryProductEntity;
import com.app.master.service.core.entity.SalesOrderEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.SalesOrderResponse;
import com.app.master.service.core.response.admin.SalesStatsResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.CustomerOrderItemRepository;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.admin.InventoryProductRepository;
import com.app.master.service.repository.admin.SalesOrderRepository;
import com.app.master.service.service.admin.SalesOrderService;
import com.google.common.base.Strings;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.UUID;

@Service
public class SalesOrderServiceImpl extends AppService implements SalesOrderService {

    private final SalesOrderRepository repository;
    private final InventoryProductRepository productRepository;
    private final CustomerOrderItemRepository customerOrderItemRepository;
    private final CustomerOrderRepository customerOrderRepository;

    public SalesOrderServiceImpl(SalesOrderRepository repository,
                                  InventoryProductRepository productRepository,
                                  CustomerOrderItemRepository customerOrderItemRepository,
                                  CustomerOrderRepository customerOrderRepository) {
        this.repository = repository;
        this.productRepository = productRepository;
        this.customerOrderItemRepository = customerOrderItemRepository;
        this.customerOrderRepository = customerOrderRepository;
    }

    @Override
    @Transactional
    public UUID createSalesOrder(SalesOrderRequest request) throws VeloriaException {
        InventoryProductEntity product = productRepository.findByUuid(request.getProductUuid())
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Product not found"));

        long count = repository.countByArchiveFalse();
        String code = String.format("SAL-%d-%04d", Year.now().getValue(), count + 1);

        int qty = request.getQuantity() != null && request.getQuantity() > 0 ? request.getQuantity() : 1;
        long unitPrice = product.getSellingPrice() != null ? product.getSellingPrice() : (product.getPrice() != null ? product.getPrice() : 0L);
        long total = unitPrice * qty;
        String currency = !Strings.isNullOrEmpty(request.getCurrency())
                ? request.getCurrency()
                : product.getPriceCurrency() != null ? product.getPriceCurrency().name() : "INR";

        SalesOrderEntity entity = SalesOrderEntity.builder()
                .orderCode(code)
                .productUuid(product.getUuid())
                .productName(product.getName())
                .skuId(product.getSkuId())
                .quantity(qty)
                .unitPrice(unitPrice)
                .totalValue(total)
                .currency(currency)
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .status("ORDER_PLACED")
                .returnWindowDays(30)
                .orderPlacedAt(Instant.now())
                .build();

        return repository.save(entity).getUuid();
    }

    @Override
    public SalesOrderResponse byUuid(UUID uuid) throws VeloriaException {
        SalesOrderEntity entity = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Sales order not found"));
        return toResponse(entity);
    }

    @Override
    public Page<SalesOrderResponse> allOrders(String status, Integer month, Integer year,
                                               String search, int page, int pageSize) throws VeloriaException {
        Pageable pageable = PageRequest.of(page, pageSize);
        String statusParam = (Strings.isNullOrEmpty(status) || "All".equalsIgnoreCase(status)) ? null : status;
        String searchParam = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();
        return customerOrderItemRepository.findSalesView(statusParam, month, year, searchParam, pageable)
                .map(this::rowToResponse);
    }

    @Override
    public SalesStatsResponse getStats() throws VeloriaException {
        long orderPlaced = customerOrderRepository.countByStatusAndArchiveFalse("ORDER_PLACED");
        long inTransit   = customerOrderRepository.countByStatusAndArchiveFalse("IN_TRANSIT");
        long done        = customerOrderRepository.countByStatusAndArchiveFalse("DELIVERED");
        Long revenue     = customerOrderRepository.sumAllTotalValue();
        return SalesStatsResponse.builder()
                .orderPlaced(orderPlaced)
                .inTransit(inTransit)
                .done(done)
                .totalRevenue(revenue != null ? revenue : 0L)
                .build();
    }

    @Override
    @Transactional
    public void updateOrderStatus(String orderCode, String newStatus) throws VeloriaException {
        CustomerOrderEntity order = customerOrderRepository.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found: " + orderCode));
        order.setStatus(newStatus);
        if ("DELIVERED".equals(newStatus) && order.getDeliveredAt() == null) {
            order.setDeliveredAt(Instant.now());
        }
        customerOrderRepository.save(order);
        // mirror status on every sales_order line for this order code prefix
        repository.findAll().stream()
                .filter(s -> s.getOrderCode() != null && s.getOrderCode().startsWith(orderCode)
                        && !Boolean.TRUE.equals(s.getArchive()))
                .forEach(s -> { s.setStatus(newStatus); repository.save(s); });
    }

    @Override
    @Transactional
    public void cancelOrder(String orderCode, String reason) throws VeloriaException {
        CustomerOrderEntity order = customerOrderRepository.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found: " + orderCode));
        order.setStatus("CANCELLED");
        order.setCancelReason(reason);
        customerOrderRepository.save(order);
        repository.findAll().stream()
                .filter(s -> s.getOrderCode() != null && s.getOrderCode().startsWith(orderCode)
                        && !Boolean.TRUE.equals(s.getArchive()))
                .forEach(s -> { s.setStatus("CANCELLED"); repository.save(s); });
    }

    @Override
    public List<SalesOrderResponse> reportAll() throws VeloriaException {
        return customerOrderItemRepository.findSalesView(null, null, null, null, Pageable.unpaged())
                .map(this::rowToResponse)
                .toList();
    }

    private SalesOrderResponse rowToResponse(Object[] row) {
        UUID itemUuid     = (UUID) row[0];
        String orderCode  = (String) row[1];
        UUID productUuid  = (UUID) row[2];
        String productName = (String) row[3];
        String skuId      = (String) row[4];
        Long unitPrice    = row[5] instanceof Long l ? l : ((Number) row[5]).longValue();
        String currency   = (String) row[6];
        String customerName       = (String) row[7];
        String customerEmail      = (String) row[8];
        String status             = (String) row[9];
        Instant placedAt          = row[10] instanceof Timestamp ts ? ts.toInstant() : (Instant) row[10];
        String selectedDimension  = row.length > 11 ? (String) row[11] : null;

        return SalesOrderResponse.builder()
                .uuid(itemUuid)
                .orderCode(orderCode)
                .productUuid(productUuid)
                .productName(productName)
                .skuId(skuId)
                .quantity(1)
                .unitPrice(unitPrice)
                .totalValue(unitPrice)
                .currency(currency)
                .customerName(customerName)
                .customerEmail(customerEmail)
                .status(status)
                .selectedDimension(selectedDimension)
                .returnWindowDays(30)
                .orderPlacedAt(placedAt)
                .build();
    }

    private SalesOrderResponse toResponse(SalesOrderEntity e) {
        return SalesOrderResponse.builder()
                .uuid(e.getUuid())
                .orderCode(e.getOrderCode())
                .productUuid(e.getProductUuid())
                .productName(e.getProductName())
                .skuId(e.getSkuId())
                .quantity(e.getQuantity())
                .unitPrice(e.getUnitPrice())
                .totalValue(e.getTotalValue())
                .currency(e.getCurrency())
                .customerName(e.getCustomerName())
                .customerEmail(e.getCustomerEmail())
                .status(e.getStatus())
                .returnWindowDays(e.getReturnWindowDays())
                .orderPlacedAt(e.getOrderPlacedAt())
                .build();
    }
}
