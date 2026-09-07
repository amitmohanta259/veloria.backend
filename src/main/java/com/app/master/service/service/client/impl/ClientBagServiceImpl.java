package com.app.master.service.service.client.impl;

import com.app.master.service.core.entity.BusinessDetailsEntity;
import com.app.master.service.core.entity.CustomerBagEntity;
import com.app.master.service.core.entity.UserAddressEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.AddToCartRequest;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.client.CartGstPreviewResponse;
import com.app.master.service.core.response.client.CustomerBagItemResponse;
import com.app.master.service.core.service.AwsService;
import com.app.master.service.repository.admin.BusinessDetailsRepository;
import com.app.master.service.repository.client.CustomerBagRepository;
import com.app.master.service.repository.client.UserAddressRepository;
import com.app.master.service.service.admin.GstCalculationService;
import com.app.master.service.service.client.ClientBagService;
import com.app.master.service.service.client.ClientSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientBagServiceImpl implements ClientBagService {

    private final ClientSessionStore sessionStore;
    private final CustomerBagRepository bagRepository;
    private final AwsService awsService;
    private final BusinessDetailsRepository businessDetailsRepository;
    private final UserAddressRepository userAddressRepository;
    private final GstCalculationService gstCalculationService;

    private String presign(String key) {
        if (key == null) return null;
        try {
            return awsService.getViewablePreSignedUrl(key);
        } catch (IOException e) {
            log.warn("Failed to presign bag image key: {}", key, e);
            return key;
        }
    }

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
                .productImage(presign(row[8] != null ? row[8].toString() : null))
                .stockStatus(mapStockStatus(row[9] != null ? ((Number) row[9]).longValue() : 0L))
                .size(row[10] != null ? row[10].toString() : null)
                .build()
        ).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void addToBag(String token, AddToCartRequest request) throws VeloriaException {
        String userId = resolveUserId(token);
        bagRepository.findByUserIdAndProductUuidAndSizeAndArchiveFalse(userId, request.getProductUuid(), request.getSize())
                .ifPresentOrElse(
                        existing -> {
                            existing.setQuantity(existing.getQuantity() + Math.max(1, request.getQuantity()));
                            existing.setUpdatedAt(Instant.now());
                            bagRepository.save(existing);
                        },
                        () -> bagRepository.save(CustomerBagEntity.builder()
                                .userId(userId)
                                .productUuid(request.getProductUuid())
                                .size(request.getSize())
                                .quantity(Math.max(1, request.getQuantity()))
                                .addedAt(Instant.now())
                                .updatedAt(Instant.now())
                                .build())
                );
    }

    @Override
    @Transactional
    public void updateQuantity(String token, UUID productUuid, int quantity, String size) throws VeloriaException {
        String userId = resolveUserId(token);
        CustomerBagEntity item = (size != null && !size.isBlank()
                ? bagRepository.findByUserIdAndProductUuidAndSizeAndArchiveFalse(userId, productUuid, size)
                : bagRepository.findFirstByUserIdAndProductUuidAndArchiveFalse(userId, productUuid))
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
    public void removeFromBag(String token, UUID productUuid, String size) throws VeloriaException {
        String userId = resolveUserId(token);
        (size != null && !size.isBlank()
                ? bagRepository.findByUserIdAndProductUuidAndSizeAndArchiveFalse(userId, productUuid, size)
                : bagRepository.findFirstByUserIdAndProductUuidAndArchiveFalse(userId, productUuid))
                .ifPresent(item -> {
                    item.setArchive(true);
                    bagRepository.save(item);
                });
    }

    @Override
    @Transactional
    public void clearBag(String token) throws VeloriaException {
        String userId = resolveUserId(token);
        bagRepository.findAllByUserIdAndArchiveFalse(userId)
                .forEach(item -> {
                    item.setArchive(true);
                    bagRepository.save(item);
                });
    }

    @Override
    public CartGstPreviewResponse getGstPreview(String token) throws VeloriaException {
        String userId = resolveUserId(token);

        String sellerStateCode = businessDetailsRepository.findFirstByArchiveFalseOrderByIdAsc()
                .map(BusinessDetailsEntity::getSellerStateCode).orElse(null);

        String buyerStateCode = userAddressRepository
                .findByUserIdAndArchiveFalseOrderByIsDefaultDescCreatedAtAsc(userId)
                .stream().findFirst()
                .map(UserAddressEntity::getStateCode)
                .orElse(null);

        List<Object[]> rows = bagRepository.findBagItemsForGst(userId);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        long totalCgst = 0, totalSgst = 0, totalIgst = 0, subtotal = 0;
        List<CartGstPreviewResponse.ItemGst> itemGsts = new ArrayList<>();

        for (Object[] row : rows) {
            String productUuid = row[0].toString();
            int quantity = ((Number) row[1]).intValue();
            long unitPrice = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            String hsnCode = row[3] != null ? row[3].toString() : null;

            GstCalculationService.GstResult gst = gstCalculationService.calculate(
                    hsnCode, unitPrice, buyerStateCode, sellerStateCode, today);

            long itemCgst = gst.cgstAmount() * quantity;
            long itemSgst = gst.sgstAmount() * quantity;
            long itemIgst = gst.igstAmount() * quantity;
            long taxableValue = unitPrice * quantity;

            totalCgst += itemCgst;
            totalSgst += itemSgst;
            totalIgst += itemIgst;
            subtotal += taxableValue;

            itemGsts.add(new CartGstPreviewResponse.ItemGst(
                    productUuid, hsnCode, quantity, unitPrice, taxableValue,
                    gst.cgstRateBp(), gst.sgstRateBp(), gst.igstRateBp(),
                    itemCgst, itemSgst, itemIgst, itemCgst + itemSgst + itemIgst
            ));
        }

        long totalGst = totalCgst + totalSgst + totalIgst;
        boolean interState = buyerStateCode != null && sellerStateCode != null
                && !buyerStateCode.trim().equals(sellerStateCode.trim());

        return new CartGstPreviewResponse(
                interState ? "INTER_STATE" : "INTRA_STATE",
                buyerStateCode,
                sellerStateCode,
                subtotal,
                totalCgst,
                totalSgst,
                totalIgst,
                totalGst,
                subtotal + totalGst,
                itemGsts
        );
    }
}
