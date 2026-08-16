package com.app.master.service.service.client.impl;

import com.app.master.service.core.entity.CustomerBagEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.AddToCartRequest;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.client.CustomerBagItemResponse;
import com.app.master.service.repository.client.CustomerBagRepository;
import com.app.master.service.service.client.ClientBagService;
import com.app.master.service.service.client.ClientSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClientBagServiceImpl implements ClientBagService {

    private final ClientSessionStore sessionStore;
    private final CustomerBagRepository bagRepository;

    private String resolveUserId(String token) throws VeloriaException {
        ClientSessionStore.SessionData session = sessionStore.get(token);
        if (session == null) throw new VeloriaException(ResponseCode.UNAUTHORIZED, "Session expired. Please sign in again.");
        return session.userId();
    }

    private static String mapCategory(String categoryName) {
        String lower = categoryName == null ? "" : categoryName.toLowerCase();
        return (lower.contains("accessor") || lower.contains("bag")) ? "bag" : "dress";
    }

    private static String mapStockStatus(long currentStock) {
        if (currentStock <= 0)  return "OUT_OF_STOCK";
        if (currentStock <= 10) return "LOW_IN_STOCK";
        return "IN_STOCK";
    }

    @Override
    public List<CustomerBagItemResponse> getBag(String token) throws VeloriaException {
        String userId = resolveUserId(token);
        List<Object[]> rows = bagRepository.findBagItemsWithDetails(userId);
        return rows.stream().map(row -> CustomerBagItemResponse.builder()
                .productUuid(UUID.fromString(row[0].toString()))
                .quantity(((Number) row[1]).intValue())
                .addedAt(row[2] != null ? ((java.sql.Timestamp) row[2]).toInstant() : null)
                .productName((String) row[3])
                .price(row[4] != null ? ((Number) row[4]).longValue() : null)
                .sellingPrice(row[5] != null ? ((Number) row[5]).longValue() : null)
                .priceCurrency(row[6] != null ? row[6].toString() : null)
                .category(mapCategory((String) row[7]))
                .productImage(row[8] != null ? row[8].toString() : null)
                .stockStatus(mapStockStatus(row[9] != null ? ((Number) row[9]).longValue() : 0L))
                .build()
        ).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void addToBag(String token, AddToCartRequest request) throws VeloriaException {
        String userId = resolveUserId(token);
        bagRepository.findByUserIdAndProductUuidAndArchiveFalse(userId, request.getProductUuid())
                .ifPresentOrElse(
                        existing -> {
                            existing.setQuantity(existing.getQuantity() + Math.max(1, request.getQuantity()));
                            existing.setUpdatedAt(Instant.now());
                            bagRepository.save(existing);
                        },
                        () -> bagRepository.save(CustomerBagEntity.builder()
                                .userId(userId)
                                .productUuid(request.getProductUuid())
                                .quantity(Math.max(1, request.getQuantity()))
                                .addedAt(Instant.now())
                                .updatedAt(Instant.now())
                                .build())
                );
    }

    @Override
    @Transactional
    public void updateQuantity(String token, UUID productUuid, int quantity) throws VeloriaException {
        String userId = resolveUserId(token);
        CustomerBagEntity item = bagRepository
                .findByUserIdAndProductUuidAndArchiveFalse(userId, productUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Bag item not found."));
        if (quantity <= 0) {
            item.setArchive(true);
        } else {
            item.setQuantity(quantity);
            item.setUpdatedAt(Instant.now());
        }
        bagRepository.save(item);
    }

    @Override
    @Transactional
    public void removeFromBag(String token, UUID productUuid) throws VeloriaException {
        String userId = resolveUserId(token);
        bagRepository.findByUserIdAndProductUuidAndArchiveFalse(userId, productUuid)
                .ifPresent(item -> {
                    item.setArchive(true);
                    bagRepository.save(item);
                });
    }
}
