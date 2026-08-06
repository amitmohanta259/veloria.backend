package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.InventorySubCategory;
import com.app.master.service.core.entity.InventoryCollectionEntity;
import com.app.master.service.core.entity.InventorySubCategoryEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.InventoryCollectionAllResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.InventoryCollectionRepository;
import com.app.master.service.repository.admin.InventorySubCategoryRepository;
import com.app.master.service.service.admin.InventorySubcategoryService;
import com.google.common.base.Strings;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class InventorySubcategoryServiceImpl extends AppService implements InventorySubcategoryService {

    private final InventorySubCategoryRepository repository;
    private final InventoryCollectionRepository collectionRepository;

    public InventorySubcategoryServiceImpl(InventorySubCategoryRepository repository, InventoryCollectionRepository collectionRepository) {
        this.repository = repository;
        this.collectionRepository = collectionRepository;
    }

    @Override
    public void createInventorySubCategory(InventorySubCategory subCategory) throws VeloriaException {

        InventoryCollectionEntity collectionEntity = collectionRepository.findByUuid(subCategory.getCollectionUuid())
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid collection uuid"));

        InventorySubCategoryEntity entity = InventorySubCategoryEntity.builder()
                .collectionId(collectionEntity.getId())
                .name(subCategory.getName())
                .description(subCategory.getDescription())
                .build();

        repository.save(entity);
    }

    @Override
    public void updateInventorySubCategory(UUID uuid, InventorySubCategory subCategory) throws VeloriaException {

        InventorySubCategoryEntity existing = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid sub-category uuid"));

        if (subCategory.getCollectionUuid() != null) {
            InventoryCollectionEntity collectionEntity = collectionRepository.findByUuid(subCategory.getCollectionUuid())
                    .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid collection uuid"));
            existing.setCollectionId(collectionEntity.getId());
        }

        existing.setName(subCategory.getName());
        existing.setDescription(subCategory.getDescription());
        existing.setModified(Instant.now());
//        existing.setModifiedBy(getCurrentUser().getIamId());

        repository.save(existing);
    }

    @Override
    public InventorySubCategory byUuidInventorySubCategory(UUID uuid) throws VeloriaException {

        InventorySubCategoryEntity existing = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid sub-category uuid"));

        InventorySubCategory dto = existing.toDto();
        if (existing.getCollectionId() != null) {
            collectionRepository.findById(existing.getCollectionId())
                    .ifPresent(col -> dto.setCollectionUuid(col.getUuid()));
        }
        return dto;
    }

    @Override
    public Page<InventoryCollectionAllResponse> allInventorySubCategory(int page, int pageSize, String search, UUID collectionUuid) throws VeloriaException {

        Pageable pageable = PageRequest.of(page, pageSize);
        search = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();

        return repository.allInventorySubCategory(collectionUuid, search, pageable);
    }

    @Override
    public java.util.List<InventoryCollectionAllResponse> listAllInventorySubCategory(UUID collectionUuid) throws VeloriaException {
        return collectionUuid != null
                ? repository.listAllInventorySubCategoryByCollection(collectionUuid)
                : repository.listAllInventorySubCategory();
    }

    @Override
    public void deleteInventorySubCategory(UUID uuid) throws VeloriaException {

        InventorySubCategoryEntity existing = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid sub-category uuid"));

        existing.setArchive(true);
        existing.setModified(Instant.now());
//        existing.setModifiedBy(getCurrentUser().getIamId());

        repository.save(existing);
    }

}