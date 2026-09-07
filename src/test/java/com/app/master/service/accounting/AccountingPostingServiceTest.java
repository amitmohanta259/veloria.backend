package com.app.master.service.accounting;

import com.app.master.service.core.entity.*;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.AccountingPostingService;
import com.app.master.service.service.admin.JournalService;
import com.app.master.service.service.admin.JournalService.Draft;
import com.app.master.service.service.admin.JournalService.Posting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The posting rules — which accounts each business transaction touches.
 *
 * Every rule must produce an entry that balances by construction, and the tax
 * split must come from the transaction rather than an assumed rate.
 */
class AccountingPostingServiceTest {

    private JournalService journal;
    private SalesInvoiceRepository salesInvoiceRepo;
    private AccountingPostingService service;

    @BeforeEach
    void setUp() throws Exception {
        journal = mock(JournalService.class);
        // No invoice for these orders, so the posting falls back to the order.
        salesInvoiceRepo = mock(SalesInvoiceRepository.class);
        when(salesInvoiceRepo.findByOrderCodeOrderByIdDesc(any())).thenReturn(List.of());
        service = new AccountingPostingService(journal,
                mock(CustomerOrderRepository.class), salesInvoiceRepo, mock(ExpenseRepository.class),
                mock(SalaryPaymentRepository.class), mock(PurchaseInvoiceRepository.class),
                mock(GstPaymentRepository.class));
        when(journal.post(any())).thenAnswer(i -> JournalEntryEntity.builder().id(1L).build());
    }

    private Draft captured() throws Exception {
        ArgumentCaptor<Draft> c = ArgumentCaptor.forClass(Draft.class);
        verify(journal).post(c.capture());
        return c.getValue();
    }

    private static void assertBalances(Draft d) {
        long dr = d.postings().stream().mapToLong(Posting::debitPaise).sum();
        long cr = d.postings().stream().mapToLong(Posting::creditPaise).sum();
        assertEquals(dr, cr, "the entry must balance");
        assertTrue(dr > 0, "and it must record something");
    }

    private static long amountOn(Draft d, String account) {
        return d.postings().stream().filter(p -> p.accountCode().equals(account))
                .mapToLong(p -> p.debitPaise() + p.creditPaise()).sum();
    }

    private static boolean touches(Draft d, String account) {
        return d.postings().stream().anyMatch(p -> p.accountCode().equals(account));
    }

    // ── Sales ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An intra-state sale credits CGST and SGST, never IGST")
    void intraStateSale() throws Exception {
        service.postSale(CustomerOrderEntity.builder()
                .id(1L).orderCode("VO-1").customerName("A")
                .orderPlacedAt(Instant.parse("2026-08-15T00:00:00Z"))
                .taxableValue(100000L).cgstAmount(9000L).sgstAmount(9000L).igstAmount(0L)
                .build());

        Draft d = captured();
        assertBalances(d);
        assertEquals(118000L, amountOn(d, AccountingPostingService.RECEIVABLE), "gross to receivable");
        assertEquals(100000L, amountOn(d, AccountingPostingService.SALES), "revenue is net of GST");
        assertEquals(9000L, amountOn(d, AccountingPostingService.OUTPUT_CGST));
        assertEquals(9000L, amountOn(d, AccountingPostingService.OUTPUT_SGST));
        assertFalse(touches(d, AccountingPostingService.OUTPUT_IGST));
    }

    @Test
    @DisplayName("An inter-state sale credits IGST only")
    void interStateSale() throws Exception {
        service.postSale(CustomerOrderEntity.builder()
                .id(2L).orderCode("VO-2")
                .orderPlacedAt(Instant.parse("2026-08-15T00:00:00Z"))
                .taxableValue(100000L).cgstAmount(0L).sgstAmount(0L).igstAmount(18000L)
                .build());

        Draft d = captured();
        assertBalances(d);
        assertEquals(18000L, amountOn(d, AccountingPostingService.OUTPUT_IGST));
        assertFalse(touches(d, AccountingPostingService.OUTPUT_CGST));
    }

    @Test
    @DisplayName("A sale with no value posts nothing")
    void zeroSalePostsNothing() throws Exception {
        assertNull(service.postSale(CustomerOrderEntity.builder()
                .id(3L).orderPlacedAt(Instant.now())
                .taxableValue(0L).cgstAmount(0L).sgstAmount(0L).igstAmount(0L).build()));
        verify(journal, never()).post(any());
    }

    // ── Expenses ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A vendor-registered expense puts the tax in Input GST as claimable")
    void creditableExpense() throws Exception {
        service.postExpense(ExpenseEntity.builder()
                .id(1L).voucherNumber("EXP-1").expenseType("MARKETING_AD_SPEND")
                .expenseDate(LocalDate.of(2026, 8, 31)).supplierGstin("27AAFCD5862R1ZR")
                .subtotalPaise(2500000L).taxPaise(450000L).totalPaise(2950000L)
                .build());

        Draft d = captured();
        assertBalances(d);
        assertEquals(2500000L, amountOn(d, AccountingPostingService.MARKETING), "expense is net");
        assertEquals(450000L, amountOn(d, AccountingPostingService.INPUT_GST), "tax is claimable");
        assertEquals(2950000L, amountOn(d, AccountingPostingService.PAYABLE));
    }

    @Test
    @DisplayName("Without a vendor GSTIN the tax is a cost, not a receivable")
    void nonCreditableExpense() throws Exception {
        service.postExpense(ExpenseEntity.builder()
                .id(2L).voucherNumber("EXP-2").expenseType("MARKETING_AD_SPEND")
                .expenseDate(LocalDate.of(2026, 8, 31)).supplierGstin(null)
                .subtotalPaise(2500000L).taxPaise(450000L).totalPaise(2950000L)
                .build());

        Draft d = captured();
        assertBalances(d);
        assertEquals(2950000L, amountOn(d, AccountingPostingService.MARKETING),
                "the whole gross is charged to the expense");
        assertFalse(touches(d, AccountingPostingService.INPUT_GST),
                "no claimable credit may be recorded");
    }

    @Test
    @DisplayName("Expense types route to their own accounts")
    void expenseTypeRouting() throws Exception {
        for (String[] pair : new String[][]{
                {"RENT", AccountingPostingService.RENT},
                {"UTILITIES", AccountingPostingService.UTILITIES},
                {"SOMETHING_ELSE", AccountingPostingService.OTHER_EXPENSE}}) {
            JournalService j = mock(JournalService.class);
            when(j.post(any())).thenReturn(JournalEntryEntity.builder().id(1L).build());
            AccountingPostingService s = new AccountingPostingService(j,
                    mock(CustomerOrderRepository.class), salesInvoiceRepo, mock(ExpenseRepository.class),
                    mock(SalaryPaymentRepository.class), mock(PurchaseInvoiceRepository.class),
                    mock(GstPaymentRepository.class));

            s.postExpense(ExpenseEntity.builder()
                    .id(9L).voucherNumber("E").expenseType(pair[0])
                    .expenseDate(LocalDate.of(2026, 8, 1)).supplierGstin("27AAFCD5862R1ZR")
                    .subtotalPaise(1000L).taxPaise(0L).totalPaise(1000L).build());

            ArgumentCaptor<Draft> c = ArgumentCaptor.forClass(Draft.class);
            verify(j).post(c.capture());
            assertTrue(touches(c.getValue(), pair[1]), pair[0] + " should post to " + pair[1]);
        }
    }

    // ── Payroll ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Payroll charges gross plus employer contributions, and owes the same")
    void payrollCost() throws Exception {
        service.postPayroll(SalaryPaymentEntity.builder()
                .id(1L).voucherNumber("SAL-1").paymentMonth("2026-08")
                .totalGrossPaise(18800000L).totalEmployerContributionPaise(2280000L)
                .totalEmployeeContributionPaise(2280000L).employeeCount(4)
                .build());

        Draft d = captured();
        assertBalances(d);
        assertEquals(21080000L, amountOn(d, AccountingPostingService.PAYROLL),
                "gross plus employer contributions is the cost to the business");
        assertEquals(21080000L, amountOn(d, AccountingPostingService.SALARY_PAYABLE));
        assertEquals(LocalDate.of(2026, 8, 31), d.date(), "dated to the end of the payroll month");
    }

    // ── Purchases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A purchase debits inventory net and input GST separately")
    void purchaseSplitsStockFromTax() throws Exception {
        service.postPurchase(PurchaseInvoiceEntity.builder()
                .id(1L).vendorInvoiceNumber("VINV-1").vendorName("Acme")
                .vendorInvoiceDate(LocalDate.of(2026, 9, 3))
                .taxableValue(5000000L).totalTax(900000L)
                .build());

        Draft d = captured();
        assertBalances(d);
        assertEquals(5000000L, amountOn(d, AccountingPostingService.INVENTORY));
        assertEquals(900000L, amountOn(d, AccountingPostingService.INPUT_GST));
        assertEquals(5900000L, amountOn(d, AccountingPostingService.PAYABLE));
    }

    // ── GST payment ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("A GST payment clears the liability against bank")
    void gstPaymentClearsLiability() throws Exception {
        service.postGstPayment(GstPaymentEntity.builder()
                .id(1L).paymentReference("CHLN-1").paymentDate(LocalDate.of(2026, 9, 5))
                .taxPeriod("2026-08").totalPaise(100500L)
                .build());

        Draft d = captured();
        assertBalances(d);
        assertEquals(100500L, amountOn(d, AccountingPostingService.GST_PAYABLE));
        assertEquals(100500L, amountOn(d, AccountingPostingService.BANK));
        assertEquals(LocalDate.of(2026, 9, 5), d.date(),
                "dated when cash moved, not to the tax period it settles");
    }
}
