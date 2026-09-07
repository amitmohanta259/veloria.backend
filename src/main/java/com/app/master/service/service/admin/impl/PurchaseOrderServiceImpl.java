package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.PurchaseOrderItemRequest;
import com.app.master.service.core.dto.PurchaseOrderRequest;
import com.app.master.service.core.entity.GstInputTaxEntity;
import com.app.master.service.core.entity.PurchaseOrderEntity;
import com.app.master.service.core.entity.PurchaseOrderItemEntity;
import com.app.master.service.core.entity.SupplierEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.PurchaseOrderItemResponse;
import com.app.master.service.core.response.admin.PurchaseOrderResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.GstInputTaxRepository;
import com.app.master.service.repository.admin.PurchaseOrderItemRepository;
import com.app.master.service.repository.admin.PurchaseOrderRepository;
import com.app.master.service.repository.admin.SupplierRepository;
import com.app.master.service.service.admin.GstAuditService;
import com.app.master.service.service.admin.GstMovementService;
import com.app.master.service.service.admin.GstRoundingService;
import com.app.master.service.service.admin.PdfGstExtractor;
import com.app.master.service.service.admin.PurchaseOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PurchaseOrderServiceImpl extends AppService implements PurchaseOrderService {

    private final PurchaseOrderRepository repository;
    private final PurchaseOrderItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final GstInputTaxRepository gstInputTaxRepository;
    private final PdfGstExtractor pdfGstExtractor;
    private final GstMovementService gstMovementService;
    private final GstAuditService gstAuditService;
    private final GstRoundingService gstRounding;

    public PurchaseOrderServiceImpl(PurchaseOrderRepository repository,
                                     PurchaseOrderItemRepository itemRepository,
                                     SupplierRepository supplierRepository,
                                     GstInputTaxRepository gstInputTaxRepository,
                                     PdfGstExtractor pdfGstExtractor,
                                     GstMovementService gstMovementService,
                                     GstAuditService gstAuditService,
                                     GstRoundingService gstRounding) {
        this.repository = repository;
        this.itemRepository = itemRepository;
        this.supplierRepository = supplierRepository;
        this.gstInputTaxRepository = gstInputTaxRepository;
        this.pdfGstExtractor = pdfGstExtractor;
        this.gstMovementService = gstMovementService;
        this.gstAuditService = gstAuditService;
        this.gstRounding = gstRounding;
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
            byte[] bytes = file.getBytes();

            String uploadsDir = System.getProperty("user.dir") + "/uploads/invoices/";
            Files.createDirectories(Paths.get(uploadsDir));
            String filename = uuid + "_" + file.getOriginalFilename();
            Path dest = Paths.get(uploadsDir + filename);
            Files.write(dest, bytes);
            String invoiceUrl = "http://localhost:8081/invoices/" + filename;
            po.setInvoiceUrl(invoiceUrl);
            po.setStatus("DONE");

            // Attempt PDF GST extraction
            boolean isPdf = filename.toLowerCase().endsWith(".pdf");
            String extractionStatus = "PENDING";
            if (isPdf) {
                PdfGstExtractor.GstExtractResult gst = pdfGstExtractor.extract(new ByteArrayInputStream(bytes));
                if (gst.success()) {
                    long cgstPaise = gstRounding.rupeesToPaise(gst.cgstAmount());
                    long sgstPaise = gstRounding.rupeesToPaise(gst.sgstAmount());
                    long igstPaise = gstRounding.rupeesToPaise(gst.igstAmount());
                    long taxablePaise = gstRounding.rupeesToPaise(gst.taxableValue());
                    long totalGstPaise = cgstPaise + sgstPaise + igstPaise;

                    po.setVendorGstin(gst.vendorGstin());
                    po.setCgstAmount(cgstPaise);
                    po.setSgstAmount(sgstPaise);
                    po.setIgstAmount(igstPaise);
                    po.setTaxableAmount(taxablePaise);
                    po.setTotalGst(totalGstPaise);
                    po.setVendorInvoiceNumber(gst.invoiceNumber());
                    extractionStatus = "EXTRACTED";

                    YearMonth ym = YearMonth.now();
                    String taxPeriod = ym.toString();
                    int yr = ym.getYear();
                    String financialYear = (ym.getMonthValue() >= 4)
                        ? yr + "-" + String.format("%02d", (yr + 1) % 100)
                        : (yr - 1) + "-" + String.format("%02d", yr % 100);
                    po.setTaxPeriod(taxPeriod);
                    po.setFinancialYear(financialYear);

                    Optional<SupplierEntity> supplier = supplierRepository.findByUuid(po.getSupplierUuid());
                    String vendorName = supplier.map(SupplierEntity::getName).orElse(null);
                    String vendorGstin = gst.vendorGstin() != null ? gst.vendorGstin()
                        : supplier.map(SupplierEntity::getGstn).orElse(null);

                    GstInputTaxEntity inputTax = GstInputTaxEntity.builder()
                        .purchaseOrderId(po.getId())
                        .purchaseOrderUuid(po.getUuid())
                        .vendorGstin(vendorGstin)
                        .vendorName(vendorName)
                        .invoiceNumber(gst.invoiceNumber())
                        .taxableValue(taxablePaise)
                        .cgstRate(gst.cgstRate())
                        .cgstAmount(cgstPaise)
                        .sgstRate(gst.sgstRate())
                        .sgstAmount(sgstPaise)
                        .igstRate(gst.igstRate())
                        .igstAmount(igstPaise)
                        .totalInputTax(totalGstPaise)
                        .taxPeriod(taxPeriod)
                        .financialYear(financialYear)
                        .itcEligibility("PENDING_REVIEW")
                        .itcStatus("PENDING_REVIEW")
                        .eligibleCgst(0L)
                        .eligibleSgst(0L)
                        .eligibleIgst(0L)
                        .ineligibleCgst(0L)
                        .ineligibleSgst(0L)
                        .ineligibleIgst(0L)
                        .reversalAmount(0L)
                        .reclaimedAmount(0L)
                        .goodsReceived(true)
                        .gstr2bMatchStatus("PENDING_REVIEW")
                        .build();

                    gstInputTaxRepository.findByPurchaseOrderUuid(po.getUuid())
                        .ifPresent(existing -> inputTax.setId(existing.getId()));
                    GstInputTaxEntity savedInputTax = gstInputTaxRepository.save(inputTax);
                    try {
                        gstMovementService.recordPurchaseMovement(savedInputTax);
                    } catch (Exception me) {
                        // Invoice is stored and input tax is recorded; the ledger
                        // gap becomes a reviewable exception, not a lost log line.
                        gstAuditService.recordException("PURCHASE_MOVEMENT", "GST_INPUT_TAX",
                                savedInputTax.getId(), po.getPoCode(), me);
                        extractionStatus = "EXTRACTED_GST_PENDING_REVIEW";
                    }
                }
            }
            po.setGstExtractionStatus(extractionStatus);
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
                .items(itemResponses)
                .vendorGstin(po.getVendorGstin())
                .vendorStateCode(po.getVendorStateCode())
                .vendorInvoiceNumber(po.getVendorInvoiceNumber())
                .vendorInvoiceDate(po.getVendorInvoiceDate() != null ? po.getVendorInvoiceDate().toString() : null)
                .taxableAmount(po.getTaxableAmount())
                .cgstAmount(po.getCgstAmount())
                .sgstAmount(po.getSgstAmount())
                .igstAmount(po.getIgstAmount())
                .totalGst(po.getTotalGst())
                .gstRate(po.getGstRate())
                .financialYear(po.getFinancialYear())
                .taxPeriod(po.getTaxPeriod())
                .gstExtractionStatus(po.getGstExtractionStatus() != null ? po.getGstExtractionStatus() : "PENDING");

        supplierRepository.findByUuid(po.getSupplierUuid()).ifPresent(s -> {
            builder.supplierName(s.getName());
            builder.supplierCode(s.getSupplierCode());
        });

        gstInputTaxRepository.findByPurchaseOrderUuid(po.getUuid()).ifPresent(itc -> {
            builder.itcEligibility(itc.getItcEligibility());
            builder.itcStatus(itc.getItcStatus());
        });

        return builder.build();
    }
}
