package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.InventoryCollection;
import com.app.master.service.core.entity.InventoryCategoryEntity;
import com.app.master.service.core.entity.InventoryCollectionEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.InventoryCollectionAllResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.InventoryCategoryRepository;
import com.app.master.service.repository.admin.InventoryCollectionRepository;
import com.app.master.service.service.admin.InventoryCollectionService;
import com.google.common.base.Strings;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class InventoryCollectionServiceImpl extends AppService implements InventoryCollectionService {

    private final InventoryCollectionRepository repository;
    private final InventoryCategoryRepository categoryRepository;

    public InventoryCollectionServiceImpl(InventoryCollectionRepository repository, InventoryCategoryRepository categoryRepository) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    public void createInventoryCollection(InventoryCollection collection) throws VeloriaException {

        InventoryCategoryEntity categoryEntity = categoryRepository.findByUuid(collection.getCategoryUuid())
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid inventory category uuid"));

        InventoryCollectionEntity entity = InventoryCollectionEntity.builder()
                .name(collection.getName())
                .description(collection.getDescription())
                .categoryId(categoryEntity.getId())
                .build();

        repository.save(entity);
    }

    @Override
    public void updateInventoryCollection(UUID uuid, InventoryCollection collection) throws VeloriaException {

        InventoryCollectionEntity existing = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid inventory collection uuid"));

        if (collection.getCategoryUuid() != null) {
            InventoryCategoryEntity categoryEntity = categoryRepository.findByUuid(collection.getCategoryUuid())
                    .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid inventory category uuid"));
            existing.setCategoryId(categoryEntity.getId());
        }

        existing.setName(collection.getName());
        existing.setDescription(collection.getDescription());
        existing.setModified(Instant.now());
//        existing.setModifiedBy(getCurrentUser().getIamId());

        repository.save(existing);
    }

    @Override
    public InventoryCollection byUuidInventoryCollection(UUID uuid) throws VeloriaException {

        InventoryCollectionEntity existing = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid inventory collection uuid"));

        return existing.toDto();
    }

    @Override
    public Page<InventoryCollectionAllResponse> allInventoryCollection(int page, int pageSize, String search, UUID categoryUuid) throws VeloriaException {

        categoryRepository.findByUuid(categoryUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid inventory category uuid"));

        Pageable pageable = PageRequest.of(page, pageSize);
        search = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();

        return repository.allInventoryCollection(categoryUuid, search, pageable);
    }

    @Override
    public void deleteInventoryCollection(UUID uuid) throws VeloriaException {

        InventoryCollectionEntity existing = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid inventory collection uuid"));

        existing.setArchive(true);
        existing.setModified(Instant.now());
//        existing.setModifiedBy(getCurrentUser().getIamId());

        repository.save(existing);
    }

}