package com.app.master.service.service.admin;

import com.app.master.service.core.dto.InventorySubCategory;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.InventoryCollectionAllResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface InventorySubcategoryService {

    void createInventorySubCategory(InventorySubCategory subCategory) throws VeloriaException;

    void updateInventorySubCategory(UUID uuid, InventorySubCategory subCategory) throws VeloriaException;

    InventorySubCategory byUuidInventorySubCategory(UUID uuid) throws VeloriaException;

    Page<InventoryCollectionAllResponse> allInventorySubCategory(int page, int pageSize, String search, UUID collectionUuid) throws VeloriaException;

    void deleteInventorySubCategory(UUID uuid) throws VeloriaException;

}
