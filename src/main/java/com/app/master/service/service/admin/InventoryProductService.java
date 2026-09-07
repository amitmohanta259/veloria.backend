package com.app.master.service.service.admin;

import com.app.master.service.core.dto.InventoryProduct;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.InventoryProductListResponse;
import com.app.master.service.core.response.admin.InventoryStatsResponse;
import com.app.master.service.core.response.admin.PerformanceLedgerResponse;
import com.app.master.service.core.response.admin.TopSellerItemResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface InventoryProductService {

    void createInventoryProduct(UUID subCategoryUuid, InventoryProduct product, List<MultipartFile> images) throws VeloriaException;

    void updateInventoryProduct(UUID productUuid, InventoryProduct product, List<MultipartFile> images) throws VeloriaException;

    InventoryProduct getInventoryProductByUuid(UUID uuid) throws VeloriaException;

    boolean toggleInventoryProduct(UUID uuid) throws VeloriaException;

    void deleteInventoryProduct(UUID uuid) throws VeloriaException;

    Page<InventoryProductListResponse> getInventoryProductList(UUID subCategoryUuid, int page, int pageSize, String search) throws VeloriaException;

    List<PerformanceLedgerResponse> getPerformanceLedger() throws VeloriaException;

    InventoryStatsResponse getInventoryStats() throws VeloriaException;

    List<TopSellerItemResponse> getTopSellers(String period) throws VeloriaException;

    void addStock(UUID productUuid, String size, long qty) throws VeloriaException;
}