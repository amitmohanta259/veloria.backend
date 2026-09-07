package com.app.master.service.service.admin;

import com.app.master.service.core.entity.ExpenseEntity;
import com.app.master.service.core.entity.ExpenseLineItemEntity;
import com.app.master.service.repository.admin.ExpenseRepository;
import com.app.master.service.repository.admin.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseRepository expenseRepo;
    private final SupplierRepository supplierRepo;

    // ---- DTOs ----

    public record VendorDto(String name, String gstin, String state) {}

    public record LineItemRequest(
        String description, String hsnCode,
        Double quantity, Double rateRupees, Double gstRate
    ) {}

    public record CreateExpenseRequest(
        String expenseType, String expenseDate,
        String supplierName, String supplierGstin, String supplierState,
        String invoiceNumber, String invoiceDate,
        String status, String notes,
        List<LineItemRequest> lineItems
    ) {}

    public record ExpenseRow(
        Long id, String uuid, String voucherNumber,
        String expenseType, String expenseDate,
        String supplierName, String supplierGstin, String supplierState,
        String invoiceNumber, long subtotalPaise, long taxPaise, long totalPaise,
        String status, String notes, List<LineItemRow> lineItems
    ) {}

    public record LineItemRow(
        Long id, String description, String hsnCode,
        double quantity, long ratePaise, double gstRate, long lineTotalPaise
    ) {}

    // ---- Public methods ----

    public List<VendorDto> getVendorsByExpenseType(String expenseType) {
        List<String> categories = switch (expenseType.toUpperCase().replace(" ", "_").replace("/", "_").replace("&", "_").replaceAll("_+", "_")) {
            case "MARKETING_AD_SPEND", "MARKETING" -> List.of("Marketing");
            case "UTILITIES_RENT", "UTILITIES" -> List.of("Utilities");
            case "OPERATIONAL_PURCHASE", "OPERATIONS", "LOGISTICS" -> List.of("Logistics", "Operations");
            default -> List.of("Logistics", "Marketing", "Utilities", "Operations");
        };

        return supplierRepo.findByCategoryInAndArchiveFalse(categories).stream()
            .map(s -> new VendorDto(s.getName(), s.getGstn(), s.getCity()))
            .toList();
    }

    public List<ExpenseRow> getAllExpenses() {
        return expenseRepo.findAllByOrderByCreatedAtDesc().stream().map(this::toRow).toList();
    }

    @Transactional
    public ExpenseRow createExpense(CreateExpenseRequest req) {
        String voucherNumber = generateVoucher();

        ExpenseEntity expense = ExpenseEntity.builder()
            .voucherNumber(voucherNumber)
            .expenseType(req.expenseType())
            .expenseDate(req.expenseDate() != null ? LocalDate.parse(req.expenseDate()) : LocalDate.now())
            .supplierName(req.supplierName())
            .supplierGstin(req.supplierGstin())
            .supplierState(req.supplierState())
            .invoiceNumber(req.invoiceNumber())
            .invoiceDate(req.invoiceDate() != null && !req.invoiceDate().isBlank() ? LocalDate.parse(req.invoiceDate()) : null)
            .status(req.status() != null ? req.status() : "PENDING")
            .notes(req.notes())
            .build();

        long subtotal = 0L;
        long tax = 0L;

        if (req.lineItems() != null) {
            for (LineItemRequest li : req.lineItems()) {
                double qty = li.quantity() != null ? li.quantity() : 1.0;
                double rate = li.rateRupees() != null ? li.rateRupees() : 0.0;
                double gst = li.gstRate() != null ? li.gstRate() : 18.0;

                long ratePaise = Math.round(rate * 100);
                long lineBase = Math.round(qty * rate * 100);
                long lineTax = Math.round(lineBase * gst / 100.0);
                long lineTotal = lineBase + lineTax;

                subtotal += lineBase;
                tax += lineTax;

                ExpenseLineItemEntity item = ExpenseLineItemEntity.builder()
                    .expense(expense)
                    .description(li.description())
                    .hsnCode(li.hsnCode())
                    .quantity(qty)
                    .ratePaise(ratePaise)
                    .gstRate(gst)
                    .lineTotalPaise(lineTotal)
                    .build();
                expense.getLineItems().add(item);
            }
        }

        expense.setSubtotalPaise(subtotal);
        expense.setTaxPaise(tax);
        expense.setTotalPaise(subtotal + tax);

        return toRow(expenseRepo.save(expense));
    }

    // ---- Private helpers ----

    private String generateVoucher() {
        int year = LocalDate.now().getYear();
        int next = expenseRepo.findMaxVoucherNumber().map(n -> n + 1).orElse(1001);
        return String.format("EXP-%d-%04d", year, next);
    }

    private ExpenseRow toRow(ExpenseEntity e) {
        List<LineItemRow> items = e.getLineItems().stream()
            .map(li -> new LineItemRow(
                li.getId(), li.getDescription(), li.getHsnCode(),
                li.getQuantity() != null ? li.getQuantity() : 1.0,
                li.getRatePaise() != null ? li.getRatePaise() : 0L,
                li.getGstRate() != null ? li.getGstRate() : 18.0,
                li.getLineTotalPaise() != null ? li.getLineTotalPaise() : 0L
            )).toList();

        return new ExpenseRow(
            e.getId(),
            e.getUuid() != null ? e.getUuid().toString() : null,
            e.getVoucherNumber(),
            e.getExpenseType(),
            e.getExpenseDate() != null ? e.getExpenseDate().toString() : null,
            e.getSupplierName(),
            e.getSupplierGstin(),
            e.getSupplierState(),
            e.getInvoiceNumber(),
            nvl(e.getSubtotalPaise()),
            nvl(e.getTaxPaise()),
            nvl(e.getTotalPaise()),
            e.getStatus(),
            e.getNotes(),
            items
        );
    }

    private long nvl(Long v) { return v != null ? v : 0L; }
}
