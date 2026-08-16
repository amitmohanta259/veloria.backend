package com.app.master.service.service.client.impl;

import com.app.master.service.core.entity.CustomerFavouriteEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.client.CustomerFavouriteResponse;
import com.app.master.service.repository.client.CustomerFavouriteRepository;
import com.app.master.service.service.client.ClientFavouriteService;
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
public class ClientFavouriteServiceImpl implements ClientFavouriteService {

    private final ClientSessionStore sessionStore;
    private final CustomerFavouriteRepository favouriteRepository;

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
    public List<CustomerFavouriteResponse> getFavourites(String token) throws VeloriaException {
        String userId = resolveUserId(token);
        List<Object[]> rows = favouriteRepository.findFavouriteItemsWithDetails(userId);
        return rows.stream().map(row -> {
            long currentStock = row[8] != null ? ((Number) row[8]).longValue() : 0;
            String stockStatus = currentStock <= 0 ? "OUT_OF_STOCK"
                    : currentStock <= 10 ? "LOW_IN_STOCK"
                    : "IN_STOCK";
            return CustomerFavouriteResponse.builder()
                    .productUuid(UUID.fromString(row[0].toString()))
                    .addedAt(row[1] != null ? ((java.sql.Timestamp) row[1]).toInstant() : null)
                    .productName((String) row[2])
                    .price(row[3] != null ? ((Number) row[3]).longValue() : null)
                    .sellingPrice(row[4] != null ? ((Number) row[4]).longValue() : null)
                    .priceCurrency(row[5] != null ? row[5].toString() : null)
                    .category(mapCategory((String) row[6]))
                    .productImage(row[7] != null ? row[7].toString() : null)
                    .stockStatus(stockStatus)
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    public List<String> getFavouriteUuids(String token) throws VeloriaException {
        String userId = resolveUserId(token);
        return favouriteRepository.findProductUuidsByUserId(userId);
    }

    @Override
    @Transactional
    public void addFavourite(String token, UUID productUuid) throws VeloriaException {
        String userId = resolveUserId(token);
        favouriteRepository.findByUserIdAndProductUuidAndArchiveFalse(userId, productUuid)
                .ifPresentOrElse(
                        existing -> { /* already favourited, no-op */ },
                        () -> favouriteRepository.save(CustomerFavouriteEntity.builder()
                                .userId(userId)
                                .productUuid(productUuid)
                                .addedAt(Instant.now())
                                .build())
                );
    }

    @Override
    @Transactional
    public void removeFavourite(String token, UUID productUuid) throws VeloriaException {
        String userId = resolveUserId(token);
        favouriteRepository.findByUserIdAndProductUuidAndArchiveFalse(userId, productUuid)
                .ifPresent(item -> {
                    item.setArchive(true);
                    favouriteRepository.save(item);
                });
    }
}
