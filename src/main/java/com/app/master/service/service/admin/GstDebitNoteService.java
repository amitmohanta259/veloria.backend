package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Debit notes (spec section 23) — an upward adjustment to an already-issued
 * supply, for a price revision or short billing.
 *
 * Mirrors the credit-note design: line items, a review lifecycle, a positive
 * ledger movement, and cross-period handling that leaves the original invoice
 * period untouched.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstDebitNoteService {

    private final GstDebitNoteRepository debitNoteRepo;
    private final GstDebitNoteItemRepository debitNoteItemRepo;
    private final GstMovementLedgerRepository movementRepo;
    private final CustomerOrderRepository orderRepo;
    private final CustomerOrderItemRepository orderItemRepo;
    private final GstCalculationService calculationService;
    private final GstRoundingService rounding;
    private final GstIdentityService identityService;
    private final GstTaxPeriodService periodService;
    private final GstAuditService auditService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** One additional-value line: which order item, and how much more is billed. */
    public record DebitLine(Long orderItemId, Integer quantity, Long additionalTaxablePaise) {}

    private Long orgId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::organizationId)
                .filter(Objects::nonNull)
                .orElseGet(identityService::defaultOrganizationId);
    }

    private Long regId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::gstRegistrationId)
                .filter(Objects::nonNull)
                .orElseGet(() -> identityService.primaryRegistration(orgId())
                        .map(GstRegistrationEntity::getId).orElse(null));
    }

    /**
     * Raises a debit note against an order.
     *
     * GST is computed on the backend from each line's original rate snapshot —
     * the caller supplies only the additional taxable value, never a tax amount.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstDebitNoteEntity create(String orderCode, List<DebitLine> lines,
                                      String reasonCode, String notes) throws VeloriaException {

        CustomerOrderEntity order = orderRepo.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Order not found: " + orderCode));

        if (lines == null || lines.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A debit note needs at least one line");
        }

        LocalDate today = LocalDate.now();
        String period = YearMonth.from(today).toString();
        periodService.assertOpen(period);

        LocalDate originalDate = order.getOrderPlacedAt() != null
                ? order.getOrderPlacedAt().atZone(IST).toLocalDate() : today;
        String originalPeriod = YearMonth.from(originalDate).toString();

        List<CustomerOrderItemEntity> orderItems =
                orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId());

        GstDebitNoteEntity dn = debitNoteRepo.save(GstDebitNoteEntity.builder()
                .debitNoteNumber(newNumber())
                .debitNoteDate(today)
                .originalOrderCode(orderCode)
                .originalOrderId(order.getId())
                .originalInvoiceDate(originalDate)
                .customerId(order.getCustomerId())
                .customerName(order.getCustomerName())
                .customerGstin(order.getCustomerGstin())
                .taxPeriod(period)
                .financialYear(financialYear(today))
                .originalTaxPeriod(originalPeriod)
                .supplyType(nvl(order.getIgstAmount()) > 0 ? "INTER_STATE" : "INTRA_STATE")
                .placeOfSupply(order.getPlaceOfSupply())
                .reasonCode(reasonCode != null ? reasonCode : "PRICE_REVISION")
                .status("PENDING_REVIEW")
                .reportingStatus("NOT_REPORTED")
                .notes(notes)
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .createdBy(securityContext.actor())
                .build());

        long taxable = 0, cgst = 0, sgst = 0, igst = 0;
        List<GstDebitNoteItemEntity> items = new ArrayList<>();

        for (DebitLine line : lines) {
            CustomerOrderItemEntity item = orderItems.stream()
                    .filter(i -> i.getId().equals(line.orderItemId()))
                    .findFirst()
                    .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST,
                            "Order item " + line.orderItemId() + " does not belong to order " + orderCode));

            long addTaxable = line.additionalTaxablePaise() != null ? line.additionalTaxablePaise() : 0L;
            if (addTaxable <= 0) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Additional taxable value must be greater than zero for order item "
                                + line.orderItemId());
            }

            // Rate comes from the original line's snapshot, not today's master.
            int cgstBp = nvlInt(item.getCgstRateBp());
            int sgstBp = nvlInt(item.getSgstRateBp());
            int igstBp = nvlInt(item.getIgstRateBp());

            long lCgst = rounding.taxOn(addTaxable, cgstBp);
            long lSgst = rounding.taxOn(addTaxable, sgstBp);
            long lIgst = rounding.taxOn(addTaxable, igstBp);

            items.add(debitNoteItemRepo.save(GstDebitNoteItemEntity.builder()
                    .debitNoteId(dn.getId())
                    .orderItemId(item.getId())
                    .productUuid(item.getProductUuid())
                    .hsnCode(item.getHsnCode())
                    .quantity(line.quantity() != null ? line.quantity() : 1)
                    .unitPricePaise(nvl(item.getUnitPricePaise()))
                    .taxableValuePaise(addTaxable)
                    .cgstRateBp(cgstBp).sgstRateBp(sgstBp).igstRateBp(igstBp)
                    .cgstAmountPaise(lCgst).sgstAmountPaise(lSgst).igstAmountPaise(lIgst)
                    .totalTaxPaise(lCgst + lSgst + lIgst)
                    .build()));

            taxable += addTaxable; cgst += lCgst; sgst += lSgst; igst += lIgst;
        }

        dn.setTaxableValuePaise(taxable);
        dn.setCgstPaise(cgst);
        dn.setSgstPaise(sgst);
        dn.setIgstPaise(igst);
        dn.setTotalDebitPaise(cgst + sgst + igst);

        // Positive movement — a debit note increases output liability.
        GstMovementLedgerEntity movement = movementRepo.save(GstMovementLedgerEntity.builder()
                .movementNumber(dn.getDebitNoteNumber())
                .movementType("DEBIT_NOTE")
                .direction(GstMovementService.DIR_OUT)
                .sourceType("DEBIT_NOTE")
                .sourceId(dn.getId())
                .sourceDocumentNumber(dn.getDebitNoteNumber())
                .originalDocumentId(order.getId())
                .originalDocumentNumber(orderCode)
                .transactionDate(today)
                .taxPeriod(period)
                .financialYear(financialYear(today))
                .businessGstin(identityService.businessGstin())
                .counterpartyName(order.getCustomerName())
                .counterpartyGstin(order.getCustomerGstin())
                .counterpartyType("CUSTOMER")
                .taxableValuePaise(taxable)
                .cgstAmountPaise(cgst)
                .sgstAmountPaise(sgst)
                .igstAmountPaise(igst)
                .totalTaxPaise(cgst + sgst + igst)
                .placeOfSupply(order.getPlaceOfSupply())
                .sellerStateCode(order.getSellerStateCode())
                .buyerStateCode(order.getBuyerStateCode())
                .supplyType(dn.getSupplyType())
                .status("POSTED")
                .reason("Debit note " + dn.getDebitNoteNumber() + " — " + dn.getReasonCode())
                .createdBy(securityContext.actor())
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .build());

        dn.setMovementId(movement.getId());
        debitNoteRepo.save(dn);

        auditService.log("GST_DEBIT_NOTE", dn.getId(), dn.getDebitNoteNumber(),
                "DEBIT_NOTE_CREATED", period, securityContext.actor());
        log.info("Debit note {} raised on order {}: {} line(s), {} paise tax, period {}→{}",
                dn.getDebitNoteNumber(), orderCode, items.size(), cgst + sgst + igst,
                originalPeriod, period);
        return dn;
    }

    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstDebitNoteEntity approve(Long id, String by) throws VeloriaException {
        GstDebitNoteEntity dn = load(id);
        requireStatus(dn, "PENDING_REVIEW", "DRAFT");
        String old = dn.getStatus();
        dn.setStatus("APPROVED");
        dn.setApprovedBy(by);
        dn.setApprovedAt(Instant.now());
        debitNoteRepo.save(dn);
        auditService.log("GST_DEBIT_NOTE", dn.getId(), dn.getDebitNoteNumber(),
                "DEBIT_NOTE_APPROVED", "status", old, "APPROVED", dn.getTaxPeriod(), by);
        return dn;
    }

    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstDebitNoteEntity issue(Long id, String by) throws VeloriaException {
        GstDebitNoteEntity dn = load(id);
        requireStatus(dn, "APPROVED");
        String old = dn.getStatus();
        dn.setStatus("ISSUED");
        dn.setIssuedAt(Instant.now());
        debitNoteRepo.save(dn);
        auditService.log("GST_DEBIT_NOTE", dn.getId(), dn.getDebitNoteNumber(),
                "DEBIT_NOTE_ISSUED", "status", old, "ISSUED", dn.getTaxPeriod(), by);
        return dn;
    }

    public List<GstDebitNoteItemEntity> itemsOf(Long id) {
        return debitNoteItemRepo.findByDebitNoteIdOrderByIdAsc(id);
    }

    private GstDebitNoteEntity load(Long id) throws VeloriaException {
        GstDebitNoteEntity dn = debitNoteRepo.findById(id)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Debit note not found: " + id));
        Long org = orgId();
        if (dn.getOrganizationId() != null && org != null && !dn.getOrganizationId().equals(org)) {
            throw new VeloriaException(ResponseCode.NOT_FOUND, "Debit note not found: " + id);
        }
        return dn;
    }

    private void requireStatus(GstDebitNoteEntity dn, String... allowed) throws VeloriaException {
        for (String s : allowed) if (s.equals(dn.getStatus())) return;
        throw new VeloriaException(ResponseCode.BAD_REQUEST, "Debit note " + dn.getDebitNoteNumber()
                + " is " + dn.getStatus() + "; expected one of " + String.join(", ", allowed));
    }

    private String newNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "DN-" + date + "-" + suffix;
    }

    private String financialYear(LocalDate d) {
        int yr = d.getYear();
        return d.getMonthValue() >= 4
                ? yr + "-" + String.format("%02d", (yr + 1) % 100)
                : (yr - 1) + "-" + String.format("%02d", yr % 100);
    }

    private static long nvl(Long v) { return v != null ? v : 0L; }
    private static int nvlInt(Integer v) { return v != null ? v : 0; }
}
