package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.InventoryProduct;
import com.app.master.service.core.entity.InventoryProductEntity;
import com.app.master.service.core.entity.InventoryProductImagesEntity;
import com.app.master.service.core.entity.InventorySubCategoryEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.service.AppService;
import com.app.master.service.core.service.AwsService;
import com.app.master.service.repository.admin.InventoryProductImagesRepository;
import com.app.master.service.repository.admin.InventoryProductRepository;
import com.app.master.service.repository.admin.InventorySubCategoryRepository;
import com.app.master.service.service.admin.InventoryProductService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class InventoryProductServiceImpl extends AppService implements InventoryProductService {

    private final InventoryProductRepository productRepository;
    private final InventoryProductImagesRepository imagesRepository;
    private final InventorySubCategoryRepository subCategoryRepository;
    private final AwsService awsService;

    public InventoryProductServiceImpl(InventoryProductRepository productRepository, InventoryProductImagesRepository imagesRepository,
                                       InventorySubCategoryRepository subCategoryRepository, AwsService awsService) {
        this.productRepository = productRepository;
        this.imagesRepository = imagesRepository;
        this.subCategoryRepository = subCategoryRepository;
        this.awsService = awsService;
    }

    @Override
    public void createInventoryProduct(InventoryProduct product, List<MultipartFile> images) throws VeloriaException {

        InventorySubCategoryEntity subCategoryEntity = subCategoryRepository.findByUuid(product.getSubCategoryUuid())
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid sub-category uuid"));

        InventoryProductEntity entity = InventoryProductEntity.builder()
                .subCategoryId(subCategoryEntity.getId())
                .name(product.getName())
                .description(product.getDescription())
                .skuId(product.getSkuId())
                .price(product.getPrice())
                .priceCurrency(product.getPriceCurrency())
                .initialStock(product.getInitialStock())
                .visibility(product.getVisibility() != null ? product.getVisibility() : null)
                .visibilityDate(Instant.now())
                .draft(product.getDraft())
                .build();

        productRepository.save(entity);
        uploadProductImages(entity.getId(), subCategoryEntity.getName(), images);
    }

    private void uploadProductImages(Long productId, String subCategoryName, List<MultipartFile> images) throws VeloriaException {
        if (images == null || images.isEmpty()) {
            return;
        }
        List<InventoryProductImagesEntity> imageEntities = new ArrayList<>();
        for (MultipartFile image : images) {
            try {
                imageEntities.add(InventoryProductImagesEntity.builder()
                        .productId(productId)
                        .image(awsService.uploadDocumentMultipart(image, awsService.getProductImagePath(subCategoryName, image.getOriginalFilename())))
                        .build());

            } catch (IOException e) {
                throw new VeloriaException(ResponseCode.INTERNAL_ERROR, "Upload failed for image " + image.getOriginalFilename() + ": " + e.getMessage());
            }
        }
        imagesRepository.saveAll(imageEntities);
    }

}