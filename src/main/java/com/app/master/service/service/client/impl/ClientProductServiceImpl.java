package com.app.master.service.service.client.impl;

import com.app.master.service.core.entity.InventoryProductImagesEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.client.ClientCategoryResponse;
import com.app.master.service.core.response.client.ClientProductDetailResponse;
import com.app.master.service.core.response.client.NewArrivalProductResponse;
import com.app.master.service.repository.admin.InventoryProductImagesRepository;
import com.app.master.service.repository.admin.InventoryProductRepository;
import com.app.master.service.service.client.ClientProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClientProductServiceImpl implements ClientProductService {

    private final InventoryProductRepository productRepository;
    private final InventoryProductImagesRepository imagesRepository;

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

        results.forEach(r -> r.setImage(firstImageByUuid.get(r.getUuid())));
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
                .image(row[1] != null ? row[1].toString() : null)
                .build()
        ).collect(Collectors.toList());
    }

    private static String mapCategory(String categoryName) {
        String lower = categoryName == null ? "" : categoryName.toLowerCase();
        return (lower.contains("accessor") || lower.contains("bag")) ? "bag" : "dress";
    }
}
