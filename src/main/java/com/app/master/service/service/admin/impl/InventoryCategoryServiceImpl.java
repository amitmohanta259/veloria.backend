package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.InventoryCategory;
import com.app.master.service.core.entity.InventoryCategoryEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.InventoryCategoryRepository;
import com.app.master.service.service.admin.InventoryCategoryService;
import com.google.common.base.Strings;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class InventoryCategoryServiceImpl extends AppService implements InventoryCategoryService {

    private final InventoryCategoryRepository repository;

    public InventoryCategoryServiceImpl(InventoryCategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public void createInventoryCategory(InventoryCategory category) throws VeloriaException {

        InventoryCategoryEntity entity = InventoryCategoryEntity.builder()
                .name(category.getName())
                .description(category.getDescription())
                .build();

        repository.save(entity);
    }

    @Override
    public void updateInventoryCategory(UUID uuid, InventoryCategory category) throws VeloriaException {

        InventoryCategoryEntity entity = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid uuid"));

        entity.setName(category.getName());
        entity.setDescription(category.getDescription());
        entity.setModified(Instant.now());
//        entity.setModifiedBy(getCurrentUser().getIamId());

        repository.save(entity);
    }

    @Override
    public InventoryCategory byUuidInventoryCategory(UUID uuid) throws VeloriaException {

        InventoryCategoryEntity entity = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid uuid"));

        return entity.toDto();
    }

    @Override
    public Page<InventoryCategory> allInventoryCategory(int page, int pageSize, String search) throws VeloriaException {

        Pageable pageable = PageRequest.of(page, pageSize);
        search = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();

        return repository.allInventoryCategory(search, pageable);
    }

    @Override
    public void deleteInventoryCategory(UUID uuid) throws VeloriaException {

        InventoryCategoryEntity entity = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid uuid"));

        entity.setArchive(true);
//        entity.setModifiedBy(getCurrentUser().getIamId());
        entity.setModified(Instant.now());

        repository.save(entity);
    }

}