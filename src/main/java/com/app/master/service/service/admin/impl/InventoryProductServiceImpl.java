package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.InventoryProduct;
import com.app.master.service.core.entity.InventoryProductEntity;
import com.app.master.service.core.entity.InventoryProductImagesEntity;
import com.app.master.service.core.entity.InventorySubCategoryEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.InventoryProductListResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.core.service.AwsService;
import com.app.master.service.repository.admin.InventoryProductImagesRepository;
import com.app.master.service.repository.admin.InventoryProductRepository;
import com.app.master.service.repository.admin.InventorySubCategoryRepository;
import com.app.master.service.service.admin.InventoryProductService;
import com.google.common.base.Strings;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
                .visibility(product.getVisibility() != null ? product.getVisibility() : null)
                .visibilityDate(Instant.now())
                .draft(product.getDraft())
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

        subCategoryRepository.findByUuid(subCategoryUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid sub-category uuid"));

        Pageable pageable = PageRequest.of(page, pageSize);
        search = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();

        Page<InventoryProductListResponse> responses = productRepository.getInventoryProductList(subCategoryUuid, search, pageable);
        for (InventoryProductListResponse res : responses) {
            List<String> images = imagesRepository.getInventoryProductListImage(res.getUuid());
            for (int i = 0; i < images.size(); i++) {
                try {
                    images.set(i, awsService.getViewablePreSignedUrl(images.get(i)));
                } catch (Exception e) {
                    continue;
                }
            }
            res.setImages(images);
        }
        return responses;
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

    private String getSubCategoryName(Long subCategoryId) throws VeloriaException {
        return subCategoryRepository.findById(subCategoryId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid sub-category id"))
                .getName();
    }

}