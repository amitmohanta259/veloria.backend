package com.app.master.service.service.admin;

import com.app.master.service.core.dto.InventoryCollection;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.InventoryCollectionAllResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface InventoryCollectionService {

    void createInventoryCollection(InventoryCollection collection) throws VeloriaException;

    void updateInventoryCollection(UUID uuid, InventoryCollection collection) throws VeloriaException;

    InventoryCollection byUuidInventoryCollection(UUID uuid) throws VeloriaException;

    Page<InventoryCollectionAllResponse> allInventoryCollection(int page, int pageSize, String search, UUID categoryUuid) throws VeloriaException;

    void deleteInventoryCollection(UUID uuid) throws VeloriaException;

}