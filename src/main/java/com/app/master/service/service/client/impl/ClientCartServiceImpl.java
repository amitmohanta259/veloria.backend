package com.app.master.service.service.client.impl;

import com.app.master.service.core.entity.CustomerCartEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.AddToCartRequest;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.client.CustomerCartItemResponse;
import com.app.master.service.repository.client.CustomerCartRepository;
import com.app.master.service.service.client.ClientCartService;
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
public class ClientCartServiceImpl implements ClientCartService {

    private final ClientSessionStore sessionStore;
    private final CustomerCartRepository cartRepository;

    private String resolveUserId(String token) throws VeloriaException {
        ClientSessionStore.SessionData session = sessionStore.get(token);
        if (session == null) throw new VeloriaException(ResponseCode.UNAUTHORIZED, "Session expired. Please sign in again.");
        return session.userId();
    }

    private static String mapCategory(String categoryName) {
        String lower = categoryName == null ? "" : categoryName.toLowerCase();
        return (lower.contains("accessor") || lower.contains("bag")) ? "bag" : "dress";
    }

    @Override
    public List<CustomerCartItemResponse> getCart(String token) throws VeloriaException {
        String userId = resolveUserId(token);
        List<Object[]> rows = cartRepository.findCartItemsWithDetails(userId);
        return rows.stream().map(row -> CustomerCartItemResponse.builder()
                .productUuid(UUID.fromString(row[0].toString()))
                .quantity(((Number) row[1]).intValue())
                .addedAt(row[2] != null ? ((java.sql.Timestamp) row[2]).toInstant() : null)
                .productName((String) row[3])
                .price(row[4] != null ? ((Number) row[4]).longValue() : null)
                .sellingPrice(row[5] != null ? ((Number) row[5]).longValue() : null)
                .priceCurrency(row[6] != null ? row[6].toString() : null)
                .category(mapCategory((String) row[7]))
                .productImage(row[8] != null ? row[8].toString() : null)
                .build()
        ).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void addToCart(String token, AddToCartRequest request) throws VeloriaException {
        String userId = resolveUserId(token);
        cartRepository.findByUserIdAndProductUuidAndArchiveFalse(userId, request.getProductUuid())
                .ifPresentOrElse(
                        existing -> {
                            existing.setQuantity(existing.getQuantity() + Math.max(1, request.getQuantity()));
                            existing.setUpdatedAt(Instant.now());
                            cartRepository.save(existing);
                        },
                        () -> cartRepository.save(CustomerCartEntity.builder()
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
        CustomerCartEntity item = cartRepository
                .findByUserIdAndProductUuidAndArchiveFalse(userId, productUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Cart item not found."));
        if (quantity <= 0) {
            item.setArchive(true);
        } else {
            item.setQuantity(quantity);
            item.setUpdatedAt(Instant.now());
        }
        cartRepository.save(item);
    }

    @Override
    @Transactional
    public void removeFromCart(String token, UUID productUuid) throws VeloriaException {
        String userId = resolveUserId(token);
        cartRepository.findByUserIdAndProductUuidAndArchiveFalse(userId, productUuid)
                .ifPresent(item -> {
                    item.setArchive(true);
                    cartRepository.save(item);
                });
    }
}
