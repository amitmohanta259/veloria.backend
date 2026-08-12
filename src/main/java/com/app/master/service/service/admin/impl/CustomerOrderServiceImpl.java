package com.app.master.service.service.admin.impl;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.entity.CustomerOrderItemEntity;
import com.app.master.service.core.entity.InventoryProductEntity;
import com.app.master.service.core.entity.UserEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.CustomerDetailResponse;
import com.app.master.service.core.response.admin.CustomerOrderItemResponse;
import com.app.master.service.core.response.admin.CustomerOrderResponse;
import com.app.master.service.core.response.admin.CustomerStatsResponse;
import com.app.master.service.core.response.admin.CustomerSummaryResponse;
import com.app.master.service.core.service.AwsService;
import com.app.master.service.repository.admin.CustomerOrderItemRepository;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.admin.InventoryProductImagesRepository;
import com.app.master.service.repository.admin.InventoryProductRepository;
import com.app.master.service.repository.client.UserAddressRepository;
import com.app.master.service.repository.client.UserRepository;
import com.app.master.service.service.admin.CustomerOrderService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class CustomerOrderServiceImpl implements CustomerOrderService {

    private final CustomerOrderRepository customerOrderRepository;
    private final CustomerOrderItemRepository customerOrderItemRepository;
    private final InventoryProductRepository inventoryProductRepository;
    private final InventoryProductImagesRepository imagesRepository;
    private final AwsService awsService;
    private final UserRepository userRepository;
    private final UserAddressRepository userAddressRepository;

    public CustomerOrderServiceImpl(CustomerOrderRepository customerOrderRepository,
                                    CustomerOrderItemRepository customerOrderItemRepository,
                                    InventoryProductRepository inventoryProductRepository,
                                    InventoryProductImagesRepository imagesRepository,
                                    AwsService awsService,
                                    UserRepository userRepository,
                                    UserAddressRepository userAddressRepository) {
        this.customerOrderRepository = customerOrderRepository;
        this.customerOrderItemRepository = customerOrderItemRepository;
        this.inventoryProductRepository = inventoryProductRepository;
        this.imagesRepository = imagesRepository;
        this.awsService = awsService;
        this.userRepository = userRepository;
        this.userAddressRepository = userAddressRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerOrderResponse> getOrdersByCustomer(String customerId, int page, int pageSize) throws VeloriaException {
        Page<CustomerOrderEntity> orderPage = customerOrderRepository.findByCustomerId(
                customerId, PageRequest.of(page, pageSize));

        List<Long> orderIds = orderPage.getContent().stream().map(CustomerOrderEntity::getId).toList();
        List<CustomerOrderItemEntity> allItems = orderIds.isEmpty() ? List.of() :
                customerOrderItemRepository.findByCustomerOrderIdInAndArchiveFalseOrderByIdAsc(orderIds);

        Map<Long, List<CustomerOrderItemEntity>> itemsByOrderId = allItems.stream()
                .collect(Collectors.groupingBy(CustomerOrderItemEntity::getCustomerOrderId));

        Map<UUID, InventoryProductEntity> productMap = buildProductMap(allItems);
        Map<UUID, String> imageMap = buildImageMap(productMap.keySet().stream().toList());

        return orderPage.map(order -> toResponse(order,
                itemsByOrderId.getOrDefault(order.getId(), List.of()),
                productMap, imageMap));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerOrderResponse getOrderByUuid(UUID uuid) throws VeloriaException {
        CustomerOrderEntity order = customerOrderRepository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found"));

        List<CustomerOrderItemEntity> items =
                customerOrderItemRepository.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId());

        Map<UUID, InventoryProductEntity> productMap = buildProductMap(items);
        Map<UUID, String> imageMap = buildImageMap(productMap.keySet().stream().toList());

        return toResponse(order, items, productMap, imageMap);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerStatsResponse getStats(String customerId) {
        long totalOrders = customerOrderRepository.countByCustomerIdAndArchiveFalse(customerId);
        long totalValue = customerOrderRepository.sumTotalValueByCustomerId(customerId);
        long avg = totalOrders > 0 ? totalValue / totalOrders : 0L;
        return CustomerStatsResponse.builder()
                .totalOrders(totalOrders)
                .totalLifetimeValue(totalValue)
                .averageOrderValue(avg)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerSummaryResponse> getCustomerList(String search, String status, int page, int pageSize) {
        String searchParam = (search == null || search.isBlank()) ? null : search.trim();
        String statusParam = (status == null || status.isBlank() || "All".equalsIgnoreCase(status)) ? null : status;
        Page<Object[]> rows = customerOrderRepository.findCustomerSummaryList(searchParam, statusParam, PageRequest.of(page, pageSize));
        return rows.map(r -> CustomerSummaryResponse.builder()
                .customerId((String) r[0])
                .name((String) r[1])
                .email((String) r[2])
                .phone((String) r[3])
                .joiningDate(r[4] != null ? r[4].toString() : null)
                .purchases(((Number) r[5]).longValue())
                .cancellationsReturns(((Number) r[6]).longValue())
                .lifetimeValue(((Number) r[7]).longValue())
                .status((String) r[8])
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerDetailResponse getCustomerDetail(String customerId) {
        Optional<UserEntity> userOpt = userRepository.findByUuidString(customerId);
        String name = userOpt.map(u -> {
            String full = "";
            if (u.getFirstName() != null) full += u.getFirstName();
            if (u.getMiddleName() != null) full += (full.isEmpty() ? "" : " ") + u.getMiddleName();
            if (u.getLastName() != null) full += (full.isEmpty() ? "" : " ") + u.getLastName();
            return full.isEmpty() ? null : full;
        }).orElse(null);

        List<CustomerDetailResponse.Address> addresses = userAddressRepository
                .findByUserIdAndArchiveFalseOrderByIsDefaultDescCreatedAtAsc(customerId)
                .stream()
                .map(a -> CustomerDetailResponse.Address.builder()
                        .uuid(a.getUuid())
                        .receiverName(a.getReceiverName())
                        .phone(a.getPhone())
                        .address(a.getAddress())
                        .isDefault(Boolean.TRUE.equals(a.getIsDefault()))
                        .build())
                .toList();

        return CustomerDetailResponse.builder()
                .name(name)
                .email(userOpt.map(UserEntity::getEmail).orElse(null))
                .phone(userOpt.map(UserEntity::getPhone).orElse(null))
                .joiningDate(userOpt.map(UserEntity::getCreated).orElse(null))
                .addresses(addresses)
                .build();
    }

    private Map<UUID, InventoryProductEntity> buildProductMap(List<CustomerOrderItemEntity> items) {
        List<UUID> productUuids = items.stream()
                .map(CustomerOrderItemEntity::getProductUuid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (productUuids.isEmpty()) return Map.of();
        return inventoryProductRepository.findByUuidInAndArchiveFalse(productUuids).stream()
                .collect(Collectors.toMap(InventoryProductEntity::getUuid, Function.identity()));
    }

    private Map<UUID, String> buildImageMap(List<UUID> productUuids) {
        if (productUuids.isEmpty()) return Map.of();
        Map<UUID, String> imageMap = new HashMap<>();
        for (Object[] row : imagesRepository.getInventoryProductListImages(productUuids)) {
            UUID productUuid = (UUID) row[0];
            if (!imageMap.containsKey(productUuid)) {
                imageMap.put(productUuid, (String) row[1]);
            }
        }
        return imageMap;
    }

    private String resolveImageUrl(String imageKey) {
        if (imageKey == null) return null;
        if (imageKey.startsWith("http://") || imageKey.startsWith("https://")) return imageKey;
        try {
            return awsService.getViewablePreSignedUrl(imageKey);
        } catch (Exception e) {
            return null;
        }
    }

    private CustomerOrderResponse toResponse(CustomerOrderEntity order,
                                             List<CustomerOrderItemEntity> itemEntities,
                                             Map<UUID, InventoryProductEntity> productMap,
                                             Map<UUID, String> imageMap) {
        List<CustomerOrderItemResponse> items = itemEntities.stream()
                .map(item -> {
                    InventoryProductEntity product = item.getProductUuid() != null
                            ? productMap.get(item.getProductUuid()) : null;
                    String imageKey = item.getProductUuid() != null
                            ? imageMap.get(item.getProductUuid()) : null;
                    return CustomerOrderItemResponse.builder()
                            .uuid(item.getUuid())
                            .productUuid(item.getProductUuid())
                            .productName(product != null ? product.getName() : "—")
                            .skuId(product != null ? product.getSkuId() : null)
                            .productImageUrl(resolveImageUrl(imageKey))
                            .price(product != null ? product.getPrice() : 0L)
                            .currency(item.getCurrency())
                            .dimensions(item.getSelectedDimension())
                            .comment(item.getComment())
                            .rating(item.getRating())
                            .reasonForReturn(item.getReasonForReturn())
                            .build();
                })
                .toList();

        return CustomerOrderResponse.builder()
                .uuid(order.getUuid())
                .orderCode(order.getOrderCode())
                .customerId(order.getCustomerId())
                .customerName(order.getCustomerName())
                .customerEmail(order.getCustomerEmail())
                .deliveryLocation(order.getDeliveryLocation())
                .totalValue(order.getTotalValue())
                .currency(order.getCurrency())
                .status(order.getStatus())
                .orderPlacedAt(order.getOrderPlacedAt())
                .items(items)
                .build();
    }
}
