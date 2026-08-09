package com.app.master.service.service.admin;

import com.app.master.service.core.dto.PurchaseOrderRequest;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.PurchaseOrderResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface PurchaseOrderService {

    UUID createPurchaseOrder(PurchaseOrderRequest request) throws VeloriaException;

    PurchaseOrderResponse byUuidPurchaseOrder(UUID uuid) throws VeloriaException;

    List<PurchaseOrderResponse> listBySupplier(UUID supplierUuid) throws VeloriaException;

    List<PurchaseOrderResponse> listAll() throws VeloriaException;

    void updateStatus(UUID uuid, String status) throws VeloriaException;

    String uploadInvoice(UUID uuid, MultipartFile file) throws VeloriaException;
}
