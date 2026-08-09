package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.PurchaseOrderItemRequest;
import com.app.master.service.core.dto.PurchaseOrderRequest;
import com.app.master.service.core.entity.PurchaseOrderEntity;
import com.app.master.service.core.entity.PurchaseOrderItemEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.PurchaseOrderItemResponse;
import com.app.master.service.core.response.admin.PurchaseOrderResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.PurchaseOrderItemRepository;
import com.app.master.service.repository.admin.PurchaseOrderRepository;
import com.app.master.service.repository.admin.SupplierRepository;
import com.app.master.service.service.admin.PurchaseOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Year;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PurchaseOrderServiceImpl extends AppService implements PurchaseOrderService {

    private final PurchaseOrderRepository repository;
    private final PurchaseOrderItemRepository itemRepository;
    private final SupplierRepository supplierRepository;

    public PurchaseOrderServiceImpl(PurchaseOrderRepository repository,
                                     PurchaseOrderItemRepository itemRepository,
                                     SupplierRepository supplierRepository) {
        this.repository = repository;
        this.itemRepository = itemRepository;
        this.supplierRepository = supplierRepository;
    }

    @Override
    @Transactional
    public UUID createPurchaseOrder(PurchaseOrderRequest request) throws VeloriaException {
        supplierRepository.findByUuid(request.getSupplierUuid())
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid supplier uuid"));

        long count = repository.count();
        String poCode = String.format("PO-%d-%04d", Year.now().getValue(), count + 1);

        long total = 0;
        if (request.getItems() != null) {
            for (PurchaseOrderItemRequest item : request.getItems()) {
                long unitCost = Math.round((item.getUnitCost() != null ? item.getUnitCost() : 0.0) * 100);
                total += unitCost * (item.getQuantity() != null ? item.getQuantity() : 0);
            }
        }

        PurchaseOrderEntity po = PurchaseOrderEntity.builder()
                .poCode(poCode)
                .supplierUuid(request.getSupplierUuid())
                .status("IN_PROGRESS")
                .totalValue(total)
                .currency(request.getCurrency())
                .paymentTerms(request.getPaymentTerms())
                .notes(request.getNotes())
                .build();

        repository.save(po);

        if (request.getItems() != null) {
            for (PurchaseOrderItemRequest item : request.getItems()) {
                long unitCost = Math.round((item.getUnitCost() != null ? item.getUnitCost() : 0.0) * 100);
                int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                PurchaseOrderItemEntity itemEntity = PurchaseOrderItemEntity.builder()
                        .purchaseOrderId(po.getId())
                        .productName(item.getProductName())
                        .skuId(item.getSkuId())
                        .unitCost(unitCost)
                        .quantity(qty)
                        .lineTotal(unitCost * qty)
                        .build();
                itemRepository.save(itemEntity);
            }
        }

        return po.getUuid();
    }

    @Override
    public PurchaseOrderResponse byUuidPurchaseOrder(UUID uuid) throws VeloriaException {
        PurchaseOrderEntity po = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid purchase order uuid"));

        List<PurchaseOrderItemEntity> items = itemRepository.findByPurchaseOrderIdOrderByIdAsc(po.getId());
        return buildResponse(po, items);
    }

    @Override
    public List<PurchaseOrderResponse> listBySupplier(UUID supplierUuid) throws VeloriaException {
        List<PurchaseOrderEntity> orders = repository.findBySupplierUuidAndArchiveFalseOrderByCreatedDesc(supplierUuid);
        return orders.stream()
                .map(po -> {
                    List<PurchaseOrderItemEntity> items = itemRepository.findByPurchaseOrderIdOrderByIdAsc(po.getId());
                    return buildResponse(po, items);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<PurchaseOrderResponse> listAll() throws VeloriaException {
        List<PurchaseOrderEntity> orders = repository.findByArchiveFalseOrderByCreatedDesc();
        return orders.stream()
                .map(po -> {
                    List<PurchaseOrderItemEntity> items = itemRepository.findByPurchaseOrderIdOrderByIdAsc(po.getId());
                    return buildResponse(po, items);
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateStatus(UUID uuid, String status) throws VeloriaException {
        PurchaseOrderEntity po = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid purchase order uuid"));
        po.setStatus(status);
        repository.save(po);
    }

    @Override
    @Transactional
    public String uploadInvoice(UUID uuid, MultipartFile file) throws VeloriaException {
        PurchaseOrderEntity po = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid purchase order uuid"));
        try {
            String uploadsDir = System.getProperty("user.dir") + "/uploads/invoices/";
            Files.createDirectories(Paths.get(uploadsDir));
            String filename = uuid + "_" + file.getOriginalFilename();
            Path dest = Paths.get(uploadsDir + filename);
            Files.write(dest, file.getBytes());
            String invoiceUrl = "http://localhost:8081/invoices/" + filename;
            po.setInvoiceUrl(invoiceUrl);
            po.setStatus("DONE");
            repository.save(po);
            return invoiceUrl;
        } catch (IOException e) {
            throw new VeloriaException(ResponseCode.INTERNAL_ERROR, "Failed to save invoice file: " + e.getMessage());
        }
    }

    private PurchaseOrderResponse buildResponse(PurchaseOrderEntity po, List<PurchaseOrderItemEntity> items) {
        List<PurchaseOrderItemResponse> itemResponses = items.stream()
                .map(item -> PurchaseOrderItemResponse.builder()
                        .uuid(item.getUuid())
                        .productName(item.getProductName())
                        .skuId(item.getSkuId())
                        .unitCost(item.getUnitCost())
                        .quantity(item.getQuantity())
                        .lineTotal(item.getLineTotal())
                        .build())
                .collect(Collectors.toList());

        PurchaseOrderResponse.PurchaseOrderResponseBuilder builder = PurchaseOrderResponse.builder()
                .uuid(po.getUuid())
                .poCode(po.getPoCode())
                .supplierUuid(po.getSupplierUuid())
                .status(po.getStatus())
                .totalValue(po.getTotalValue())
                .currency(po.getCurrency())
                .paymentTerms(po.getPaymentTerms())
                .notes(po.getNotes())
                .invoiceUrl(po.getInvoiceUrl())
                .created(po.getCreated() != null ? po.getCreated().toString() : null)
                .items(itemResponses);

        supplierRepository.findByUuid(po.getSupplierUuid()).ifPresent(s -> {
            builder.supplierName(s.getName());
            builder.supplierCode(s.getSupplierCode());
        });

        return builder.build();
    }
}
