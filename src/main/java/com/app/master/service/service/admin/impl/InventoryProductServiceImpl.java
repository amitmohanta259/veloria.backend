package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.InventoryProduct;
import com.app.master.service.core.entity.InventoryProductEntity;
import com.app.master.service.core.entity.InventoryProductImagesEntity;
import com.app.master.service.core.entity.InventorySubCategoryEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.InventoryProductListResponse;
import com.app.master.service.core.response.admin.InventoryStatsResponse;
import com.app.master.service.core.response.admin.PerformanceLedgerResponse;
import com.app.master.service.core.response.admin.TopSellerItemResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.core.service.AwsService;
import com.app.master.service.repository.admin.InventoryProductImagesRepository;
import com.app.master.service.repository.admin.InventoryProductRepository;
import com.app.master.service.repository.admin.InventorySubCategoryRepository;
import com.app.master.service.service.admin.InventoryProductService;
import com.google.common.base.Strings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class InventoryProductServiceImpl extends AppService implements InventoryProductService {

    private final InventoryProductRepository productRepository;
    private final InventoryProductImagesRepository imagesRepository;
    private final InventorySubCategoryRepository subCategoryRepository;
    private final AwsService awsService;
    private final Executor taskExecutor;

    public InventoryProductServiceImpl(InventoryProductRepository productRepository, InventoryProductImagesRepository imagesRepository,
                                       InventorySubCategoryRepository subCategoryRepository, AwsService awsService,
                                       @Qualifier("taskExecutor") Executor taskExecutor) {
        this.productRepository = productRepository;
        this.imagesRepository = imagesRepository;
        this.subCategoryRepository = subCategoryRepository;
        this.awsService = awsService;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void createInventoryProduct(UUID subCategoryUuid, InventoryProduct product, List<MultipartFile> images) throws VeloriaException {

        InventorySubCategoryEntity subCategoryEntity = subCategoryRepository.findByUuid(subCategoryUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid sub-category uuid"));

        InventoryProductEntity entity = InventoryProductEntity.builder()
                .subCategoryId(subCategoryEntity.getId())
                .name(product.getName())
                .description(product.getDescription())
                .skuId(product.getSkuId())
                .price(product.getPrice())
                .priceCurrency(product.getPriceCurrency())
                .initialStock(product.getInitialStock())
                .visibility(product.getVisibility())
                .visibilityDate(Instant.now())
                .draft(product.getDraft() != null ? product.getDraft() : Boolean.TRUE)
                .gender(product.getGender())
                .dimensions(product.getDimensions())
                .supplierUuid(product.getSupplierUuid())
                .build();

        productRepository.save(entity);
        uploadProductImages(entity.getId(), subCategoryEntity.getName(), images, product.getName());
    }

    @Override
    public void updateInventoryProduct(UUID productUuid, InventoryProduct product, List<MultipartFile> images) throws VeloriaException {

        InventoryProductEntity existing = productRepository.findByUuid(productUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid product uuid"));

        existing.setName(product.getName());
        existing.setDescription(product.getDescription());
        existing.setSkuId(product.getSkuId());
        existing.setPrice(product.getPrice());
        existing.setPriceCurrency(product.getPriceCurrency());
        existing.setInitialStock(product.getInitialStock());
        existing.setVisibility(product.getVisibility());
        existing.setVisibilityDate(Instant.now());
        existing.setDraft(product.getDraft());
        existing.setGender(product.getGender());
        existing.setDimensions(product.getDimensions());
        existing.setSupplierUuid(product.getSupplierUuid());

        productRepository.save(existing);
        uploadProductImages(existing.getId(), getSubCategoryName(existing.getSubCategoryId()), images, product.getName());
    }

    @Override
    public InventoryProduct getInventoryProductByUuid(UUID uuid) throws VeloriaException {

        InventoryProductEntity existing = productRepository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid product uuid"));

        return existing.toDto();
    }

    @Override
    public boolean toggleInventoryProduct(UUID uuid) throws VeloriaException {

        InventoryProductEntity existing = productRepository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid product uuid"));

        boolean newStatus = !existing.getActive();
        existing.setActive(newStatus);
        existing.setModified(Instant.now());
        productRepository.save(existing);
        return newStatus;
    }

    @Override
    public void deleteInventoryProduct(UUID uuid) throws VeloriaException {

        InventoryProductEntity existing = productRepository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid product uuid"));

        existing.setArchive(true);
        existing.setModified(Instant.now());
        productRepository.save(existing);
    }

    @Override
    public Page<InventoryProductListResponse> getInventoryProductList(UUID subCategoryUuid, int page, int pageSize, String search) throws VeloriaException {

        if (subCategoryUuid != null) {
            subCategoryRepository.findByUuid(subCategoryUuid)
                    .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid sub-category uuid"));
        }

        Pageable pageable = PageRequest.of(page, pageSize);
        search = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();

        Page<InventoryProductListResponse> responses = subCategoryUuid != null
                ? productRepository.getInventoryProductListBySubCategory(subCategoryUuid, search, pageable)
                : productRepository.getInventoryProductList(search, pageable);
        attachPresignedImages(responses.getContent());
        return responses;
    }

    private void attachPresignedImages(List<InventoryProductListResponse> products) {
        if (products.isEmpty()) {
            return;
        }

        List<UUID> productUuids = products.stream().map(InventoryProductListResponse::getUuid).collect(Collectors.toList());

        // One query for the whole page instead of one query per product.
        Map<UUID, List<String>> imageKeysByProduct = new HashMap<>();
        for (Object[] row : imagesRepository.getInventoryProductListImages(productUuids)) {
            imageKeysByProduct.computeIfAbsent((UUID) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }

        // Presign every image concurrently on the shared task executor instead of
        // one-at-a-time on the request thread.
        List<CompletableFuture<Void>> presignTasks = new ArrayList<>();
        for (InventoryProductListResponse res : products) {
            List<String> imageKeys = imageKeysByProduct.getOrDefault(res.getUuid(), List.of());
            List<String> presignedUrls = new ArrayList<>(Collections.nCopies(imageKeys.size(), null));
            res.setImages(presignedUrls);

            for (int i = 0; i < imageKeys.size(); i++) {
                int index = i;
                String imageKey = imageKeys.get(i);
                presignTasks.add(CompletableFuture.runAsync(() -> {
                    try {
                        presignedUrls.set(index, awsService.getViewablePreSignedUrl(imageKey));
                    } catch (Exception e) {
                        // leave null - same as the previous best-effort behaviour
                    }
                }, taskExecutor));
            }
        }

        CompletableFuture.allOf(presignTasks.toArray(new CompletableFuture[0])).join();
    }

    private void uploadProductImages(Long productId, String subCategoryName, List<MultipartFile> images, String productName) throws VeloriaException {
        if (images == null || images.isEmpty()) {
            return;
        }

        List<InventoryProductImagesEntity> existingImages = imagesRepository.findByProductId(productId);
        for (InventoryProductImagesEntity img : existingImages) {
            try {
                awsService.deleteKey(img.getImage());
            } catch (Exception ignored) {
            }
        }
        imagesRepository.deleteAll(existingImages);

        List<InventoryProductImagesEntity> imageEntities = new ArrayList<>();
        for (MultipartFile image : images) {
            try {
                imageEntities.add(InventoryProductImagesEntity.builder()
                        .productId(productId)
                        .image(awsService.uploadDocumentMultipart(image, awsService.getProductImagePath(subCategoryName, image.getOriginalFilename(), productName)))
                        .build());

            } catch (IOException e) {
                throw new VeloriaException(ResponseCode.INTERNAL_ERROR, "Upload failed for image " + image.getOriginalFilename() + ": " + e.getMessage());
            }
        }
        imagesRepository.saveAll(imageEntities);
    }

    @Override
    public List<PerformanceLedgerResponse> getPerformanceLedger() throws VeloriaException {
        return productRepository.findPerformanceLedger().stream()
                .map(this::rowToLedger)
                .collect(Collectors.toList());
    }

    private PerformanceLedgerResponse rowToLedger(Object[] row) {
        String category    = (String) row[0];
        String collection  = (String) row[1];
        String subCategory = (String) row[2];
        String productName = (String) row[3];
        UUID   productUuid = (UUID)   row[4];
        long   sales       = row[5] instanceof Long l ? l : ((Number) row[5]).longValue();
        long   returns     = row[6] instanceof Long l ? l : ((Number) row[6]).longValue();
        long   intent      = row[7] instanceof Long l ? l : ((Number) row[7]).longValue();
        double conversion  = row[8] instanceof Double d ? d : ((Number) row[8]).doubleValue();

        return PerformanceLedgerResponse.builder()
                .productUuid(productUuid)
                .category(category)
                .collection(collection)
                .subCategory(subCategory)
                .productName(productName)
                .sales(sales)
                .returns(returns)
                .intent(intent)
                .conversion(conversion)
                .build();
    }

    @Override
    public InventoryStatsResponse getInventoryStats() throws VeloriaException {
        List<Object[]> statsList = productRepository.findInventoryStats();
        List<Object[]> topCatList = productRepository.findTopCategory();

        Object[] stats  = statsList.isEmpty()  ? new Object[]{0L, "INR", 0L, 0L} : statsList.get(0);
        Object[] topCat = topCatList.isEmpty() ? null : topCatList.get(0);

        long totalStockValue = stats[0] instanceof Long l ? l : ((Number) stats[0]).longValue();
        String currency      = (String) stats[1];
        long lowStock        = stats[2] instanceof Long l ? l : ((Number) stats[2]).longValue();
        long outOfStock      = stats[3] instanceof Long l ? l : ((Number) stats[3]).longValue();

        String topCategory   = topCat != null && topCat.length > 0 ? (String) topCat[0] : "—";
        double topShare      = topCat != null && topCat.length > 2
                ? (topCat[2] instanceof Double d ? d : ((Number) topCat[2]).doubleValue()) : 0.0;

        return InventoryStatsResponse.builder()
                .totalStockValue(totalStockValue)
                .currency(currency)
                .lowOnStockCount(lowStock)
                .outOfStockCount(outOfStock)
                .topCategory(topCategory)
                .topCategoryShare(topShare)
                .build();
    }

    @Override
    public List<TopSellerItemResponse> getTopSellers(String period) throws VeloriaException {
        List<Object[]> rows = switch (period) {
            case "quarterly" -> productRepository.findTopSellersQuarterly();
            case "annual"    -> productRepository.findTopSellersAnnual();
            default          -> productRepository.findTopSellersMonthly();
        };
        return rows.stream().map(row -> {
            String name       = (String) row[0];
            String currency   = (String) row[1];
            long   salesCount = row[2] instanceof Long l ? l : ((Number) row[2]).longValue();
            long   revenue    = row[3] instanceof Long l ? l : ((Number) row[3]).longValue();
            String imageUrl   = (String) row[4];
            return TopSellerItemResponse.builder()
                    .name(name).salesCount(salesCount).totalRevenue(revenue)
                    .currency(currency).imageUrl(imageUrl)
                    .build();
        }).toList();
    }

    private String getSubCategoryName(Long subCategoryId) throws VeloriaException {
        return subCategoryRepository.findById(subCategoryId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid sub-category id"))
                .getName();
    }

}