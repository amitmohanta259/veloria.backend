package com.app.master.service.service.admin;

import com.app.master.service.core.dto.Supplier;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.SupplierListResponse;
import com.app.master.service.core.response.admin.SupplierStatsResponse;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface SupplierService {

    void createSupplier(Supplier supplier) throws VeloriaException;

    void updateSupplier(UUID uuid, Supplier supplier) throws VeloriaException;

    Supplier byUuidSupplier(UUID uuid) throws VeloriaException;

    Page<SupplierListResponse> allSuppliers(int page, int pageSize, String search, String category) throws VeloriaException;

    List<SupplierListResponse> listAllSuppliers() throws VeloriaException;

    void deleteSupplier(UUID uuid) throws VeloriaException;

    SupplierStatsResponse getSupplierStats() throws VeloriaException;
}
