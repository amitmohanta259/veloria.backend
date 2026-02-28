package com.app.master.service.service.admin;

import com.app.master.service.core.dto.InventoryCategory;
import com.app.master.service.core.exception.VeloriaException;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface InventoryCategoryService {

    void createInventoryCategory(InventoryCategory category) throws VeloriaException;

    void updateInventoryCategory(UUID uuid, InventoryCategory category) throws VeloriaException;

    InventoryCategory byUuidInventoryCategory(UUID uuid) throws VeloriaException;

    Page<InventoryCategory> allInventoryCategory(int page, int pageSize, String search) throws VeloriaException;

    void deleteInventoryCategory(UUID uuid) throws VeloriaException;

}