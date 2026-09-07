package com.app.master.service.service.client.impl;

import com.app.master.service.core.entity.InventoryProductImagesEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;

import java.util.Arrays;
import com.app.master.service.core.response.client.ClientCategoryResponse;
import com.app.master.service.core.response.client.ClientProductDetailResponse;
import com.app.master.service.core.response.client.NewArrivalProductResponse;
import com.app.master.service.core.response.client.ProductSizesResponse;
import com.app.master.service.core.dto.SizeStock;
import com.app.master.service.core.service.AwsService;
import com.app.master.service.repository.admin.InventoryProductImagesRepository;
import com.app.master.service.repository.admin.InventoryProductRepository;
import com.app.master.service.repository.admin.InventoryProductSizeStockRepository;
import com.app.master.service.service.client.ClientProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientProductServiceImpl implements ClientProductService {

    private final InventoryProductRepository productRepository;
    private final InventoryProductImagesRepository imagesRepository;
    private final InventoryProductSizeStockRepository sizeStockRepository;
    private final AwsService awsService;

    private String presign(String key) {
        if (key == null) return null;
        try {
            return awsService.getViewablePreSignedUrl(key);
        } catch (IOException e) {
            log.warn("Failed to presign image key: {}", key, e);
            return key;
        }
    }

    @Override
    public List<NewArrivalProductResponse> getNewArrivals() {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        return mapRowsWithImages(productRepository.findNewArrivals(since));
    }

    @Override
    public List<NewArrivalProductResponse> getAllProducts() {
        return mapRowsWithImages(productRepository.findAllProductsForClient());
    }

    @Override
    public ClientProductDetailResponse getProductDetail(UUID uuid) throws VeloriaException {
        List<Object[]> rows = productRepository.findProductByUuidForClient(uuid);
        if (rows.isEmpty()) throw new VeloriaException(ResponseCode.NOT_FOUND, "Product not found");
        Object[] row = rows.get(0);

        Long id             = ((Number) row[0]).longValue();
        String categoryName = (String) row[7];
        String collectionName = row[9] != null ? row[9].toString() : null;

        List<InventoryProductImagesEntity> images = imagesRepository.findByProductId(id);
        List<String> imageUrls = images.stream()
                .map(InventoryProductImagesEntity::getImage)
                .filter(Objects::nonNull)
                .map(this::presign)
                .collect(Collectors.toList());

        String mainImage = imageUrls.isEmpty() ? null : imageUrls.get(0);
        List<String> gallery = imageUrls.size() > 1 ? imageUrls.subList(1, imageUrls.size()) : List.of();

        return ClientProductDetailResponse.builder()
                .id(id)
                .uuid(UUID.fromString(row[1].toString()))
                .name((String) row[2])
                .description((String) row[3])
                .price(row[4] != null ? ((Number) row[4]).longValue() : null)
                .priceCurrency(row[5] != null ? row[5].toString() : null)
                .dimensions(row[6] != null ? row[6].toString() : null)
                .categoryName(categoryName)
                .category(mapCategory(categoryName))
                .sellingPrice(row[8] != null ? ((Number) row[8]).longValue() : null)
                .collectionName(collectionName)
                .mainImage(mainImage)
                .gallery(gallery)
                .build();
    }

    private List<NewArrivalProductResponse> mapRowsWithImages(List<Object[]> rows) {
        List<NewArrivalProductResponse> results = rows.stream().map(row -> {
            String categoryName = (String) row[7];
            return NewArrivalProductResponse.builder()
                    .id(((Number) row[0]).longValue())
                    .uuid(UUID.fromString(row[1].toString()))
                    .name((String) row[2])
                    .description((String) row[3])
                    .price(row[4] != null ? ((Number) row[4]).longValue() : null)
                    .priceCurrency(row[5] != null ? row[5].toString() : null)
                    .dimensions(row[6] != null ? row[6].toString() : null)
                    .categoryName(categoryName)
                    .category(mapCategory(categoryName))
                    .sellingPrice(row.length > 8 && row[8] != null ? ((Number) row[8]).longValue() : null)
                    .build();
        }).collect(Collectors.toList());

        if (results.isEmpty()) return results;

        List<UUID> uuids = results.stream().map(NewArrivalProductResponse::getUuid).collect(Collectors.toList());
        List<Object[]> imageRows = imagesRepository.getInventoryProductListImages(uuids);

        Map<UUID, String> firstImageByUuid = new LinkedHashMap<>();
        for (Object[] imageRow : imageRows) {
            UUID productUuid = UUID.fromString(imageRow[0].toString());
            firstImageByUuid.putIfAbsent(productUuid, (String) imageRow[1]);
        }

        results.forEach(r -> r.setImage(presign(firstImageByUuid.get(r.getUuid()))));
        return results;
    }

    @Override
    public List<NewArrivalProductResponse> getPopularProducts(int page, int size) {
        int offset = page * size;
        return mapRowsWithImages(productRepository.findPopularProducts(size, offset));
    }

    @Override
    public List<ClientCategoryResponse> getCategories() {
        List<Object[]> rows = productRepository.findCategoriesWithLatestImage();
        return rows.stream().map(row -> ClientCategoryResponse.builder()
                .name((String) row[0])
                .category(mapCategory((String) row[0]))
                .image(row[1] != null ? presign(row[1].toString()) : null)
                .build()
        ).collect(Collectors.toList());
    }

    @Override
    public ProductSizesResponse getProductSizes(UUID productUuid) throws VeloriaException {
        return productRepository.findByUuid(productUuid)
                .filter(p -> Boolean.FALSE.equals(p.getArchive()))
                .map(p -> {
                    List<Object[]> rows = sizeStockRepository.findCurrentStockByProductId(p.getId());
                    List<SizeStock> sizes = rows.stream().map(row -> SizeStock.builder()
                            .size((String) row[0])
                            .stock(row[1] instanceof Long l ? l : ((Number) row[1]).longValue())
                            .currentStock(row[2] instanceof Long l ? l : ((Number) row[2]).longValue())
                            .build()).collect(Collectors.toList());
                    boolean inStock = sizes.stream().anyMatch(s -> s.getCurrentStock() != null && s.getCurrentStock() > 0);
                    return ProductSizesResponse.builder().sizes(sizes).inStock(inStock).build();
                })
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Product not found"));
    }

    private static String mapCategory(String categoryName) {
        String lower = categoryName == null ? "" : categoryName.toLowerCase();
        return (lower.contains("accessor") || lower.contains("bag")) ? "bag" : "dress";
    }
}
