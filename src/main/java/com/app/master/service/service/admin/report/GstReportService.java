package com.app.master.service.service.admin.report;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.GstIdentityService;
import com.app.master.service.service.admin.GstMovementService;
import com.app.master.service.service.admin.GstTaxPeriodService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.app.master.service.service.admin.report.GstReport.Column.*;

/**
 * Builds every GST report from the accounting source of truth (spec phase 18).
 *
 * All thirteen report types are assembled here and handed to a format writer.
 * No report re-derives a figure: sales and output tax come from the movement
 * ledger, ITC from the ITC transactions, periods from the period service. That
 * is what keeps a CSV and a PDF of the same report agreeing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstReportService {

    public static final String SALES              = "GST_SALES";
    public static final String PURCHASE           = "GST_PURCHASE";
    public static final String INPUT_TAX          = "INPUT_TAX";
    public static final String OUTPUT_TAX         = "OUTPUT_TAX";
    public static final String LEDGER             = "GST_LEDGER";
    public static final String GSTR2B_RECON       = "GSTR2B_RECONCILIATION";
    public static final String GSTR1              = "GSTR1_PREPARATION";
    public static final String GSTR3B             = "GSTR3B_PREPARATION";
    public static final String HSN_SUMMARY        = "HSN_SUMMARY";
    public static final String PERIOD_SUMMARY     = "TAX_PERIOD_SUMMARY";
    public static final String TAX_PAYMENT        = "TAX_PAYMENT";
    public static final String CREDIT_NOTE        = "CREDIT_NOTE";
    public static final String DEBIT_NOTE         = "DEBIT_NOTE";

    public static final List<String> REPORT_TYPES = List.of(
            SALES, PURCHASE, INPUT_TAX, OUTPUT_TAX, LEDGER, GSTR2B_RECON,
            GSTR1, GSTR3B, HSN_SUMMARY, PERIOD_SUMMARY, TAX_PAYMENT, CREDIT_NOTE, DEBIT_NOTE);

    private final GstMovementLedgerRepository movementRepo;
    private final GstInputTaxRepository inputTaxRepo;
    private final GstCreditNoteRepository creditNoteRepo;
    private final GstDebitNoteRepository debitNoteRepo;
    private final GstPaymentRepository paymentRepo;
    private final GstItcTransactionRepository itcTxnRepo;
    private final ItcReconciliationRepository reconRepo;
    private final SalesInvoiceRepository salesInvoiceRepo;
    private final PurchaseInvoiceRepository purchaseInvoiceRepo;
    private final GstTaxPeriodRepository periodRepo;
    private final GstTaxPeriodService periodService;
    private final GstIdentityService identityService;

    /** Builds a report. The period is required for everything except the ledger. */
    public GstReport build(Long orgId, String reportType, String period) throws VeloriaException {
        if (reportType == null || !REPORT_TYPES.contains(reportType)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Unknown report type '" + reportType + "'. Valid types: " + REPORT_TYPES);
        }
        return switch (reportType) {
            case SALES          -> sales(orgId, period);
            case PURCHASE       -> purchase(orgId, period);
            case INPUT_TAX      -> inputTax(orgId, period);
            case OUTPUT_TAX     -> outputTax(orgId, period);
            case LEDGER         -> ledger(orgId, period);
            case GSTR2B_RECON   -> gstr2bRecon(orgId, period);
            case GSTR1          -> gstr1(orgId, period);
            case GSTR3B         -> gstr3b(orgId, period);
            case HSN_SUMMARY    -> hsnSummary(orgId, period);
            case PERIOD_SUMMARY -> periodSummary(orgId);
            case TAX_PAYMENT    -> taxPayment(orgId, period);
            case CREDIT_NOTE    -> creditNotes(orgId, period);
            case DEBIT_NOTE     -> debitNotes(orgId, period);
            default -> throw new VeloriaException(ResponseCode.BAD_REQUEST, "Unhandled report: " + reportType);
        };
    }

    // ── Ledger-derived reports ───────────────────────────────────────────────

    private List<GstMovementLedgerEntity> movements(Long orgId, String period) {
        return movementRepo.findByTaxPeriodOrderByCreatedAtDesc(period).stream()
                .filter(m -> orgId == null || orgId.equals(m.getOrganizationId()))
                .filter(m -> !"SUPERSEDED".equals(m.getStatus()) && !"CANCELLED".equals(m.getStatus()))
                .toList();
    }

    private GstReport sales(Long orgId, String period) {
        var b = GstReport.of(SALES, "GST Sales Report").period(period)
                .column(text("invoiceNumber", "Invoice"))
                .column(date("date", "Date"))
                .column(text("customer", "Customer"))
                .column(text("customerGstin", "Customer GSTIN"))
                .column(text("placeOfSupply", "POS"))
                .column(text("supplyType", "Supply"))
                .column(text("hsn", "HSN"))
                .column(number("quantity", "Qty"))
                .column(money("taxable", "Taxable"))
                .column(money("cgst", "CGST"))
                .column(money("sgst", "SGST"))
                .column(money("igst", "IGST"))
                .column(money("total", "Total Tax"));

        long taxable = 0, cgst = 0, sgst = 0, igst = 0;
        for (GstMovementLedgerEntity m : movements(orgId, period)) {
            if (!GstMovementService.SALE_OUTPUT.equals(m.getMovementType())) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("invoiceNumber", m.getSourceDocumentNumber());
            r.put("date", String.valueOf(m.getTransactionDate()));
            r.put("customer", m.getCounterpartyName());
            r.put("customerGstin", m.getCounterpartyGstin());
            r.put("placeOfSupply", m.getPlaceOfSupply());
            r.put("supplyType", m.getSupplyType());
            r.put("hsn", m.getHsnCode());
            r.put("quantity", m.getQuantity());
            r.put("taxable", nvl(m.getTaxableValuePaise()));
            r.put("cgst", nvl(m.getCgstAmountPaise()));
            r.put("sgst", nvl(m.getSgstAmountPaise()));
            r.put("igst", nvl(m.getIgstAmountPaise()));
            r.put("total", nvl(m.getTotalTaxPaise()));
            b.row(r);
            taxable += nvl(m.getTaxableValuePaise()); cgst += nvl(m.getCgstAmountPaise());
            sgst += nvl(m.getSgstAmountPaise()); igst += nvl(m.getIgstAmountPaise());
        }
        return totals(b, taxable, cgst, sgst, igst).build();
    }

    private GstReport outputTax(Long orgId, String period) {
        var b = GstReport.of(OUTPUT_TAX, "Output Tax Report").period(period)
                .column(text("movement", "Movement"))
                .column(text("type", "Type"))
                .column(date("date", "Date"))
                .column(text("party", "Customer"))
                .column(money("taxable", "Taxable"))
                .column(money("cgst", "CGST"))
                .column(money("sgst", "SGST"))
                .column(money("igst", "IGST"))
                .column(money("total", "Total Tax"));

        long taxable = 0, cgst = 0, sgst = 0, igst = 0;
        for (GstMovementLedgerEntity m : movements(orgId, period)) {
            if (!"OUT".equals(m.getDirection()) && !"ADJUSTMENT".equals(m.getDirection())) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("movement", m.getMovementNumber());
            r.put("type", m.getMovementType());
            r.put("date", String.valueOf(m.getTransactionDate()));
            r.put("party", m.getCounterpartyName());
            r.put("taxable", nvl(m.getTaxableValuePaise()));
            r.put("cgst", nvl(m.getCgstAmountPaise()));
            r.put("sgst", nvl(m.getSgstAmountPaise()));
            r.put("igst", nvl(m.getIgstAmountPaise()));
            r.put("total", nvl(m.getTotalTaxPaise()));
            b.row(r);
            taxable += nvl(m.getTaxableValuePaise()); cgst += nvl(m.getCgstAmountPaise());
            sgst += nvl(m.getSgstAmountPaise()); igst += nvl(m.getIgstAmountPaise());
        }
        return totals(b, taxable, cgst, sgst, igst).build();
    }

    private GstReport ledger(Long orgId, String period) {
        var b = GstReport.of(LEDGER, "GST Ledger").period(period)
                .column(text("movement", "Movement"))
                .column(date("date", "Date"))
                .column(text("period", "Period"))
                .column(text("type", "Type"))
                .column(text("direction", "Direction"))
                .column(text("party", "Party"))
                .column(text("document", "Document"))
                .column(text("hsn", "HSN"))
                .column(money("taxable", "Taxable"))
                .column(money("cgst", "CGST"))
                .column(money("sgst", "SGST"))
                .column(money("igst", "IGST"))
                .column(money("total", "Total"))
                .column(text("status", "Status"));

        List<GstMovementLedgerEntity> all = period != null
                ? movements(orgId, period)
                : movementRepo.findAll().stream()
                    .filter(m -> orgId == null || orgId.equals(m.getOrganizationId())).toList();

        long running = 0;
        for (GstMovementLedgerEntity m : all) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("movement", m.getMovementNumber());
            r.put("date", String.valueOf(m.getTransactionDate()));
            r.put("period", m.getTaxPeriod());
            r.put("type", m.getMovementType());
            r.put("direction", m.getDirection());
            r.put("party", m.getCounterpartyName());
            r.put("document", m.getSourceDocumentNumber());
            r.put("hsn", m.getHsnCode());
            r.put("taxable", nvl(m.getTaxableValuePaise()));
            r.put("cgst", nvl(m.getCgstAmountPaise()));
            r.put("sgst", nvl(m.getSgstAmountPaise()));
            r.put("igst", nvl(m.getIgstAmountPaise()));
            r.put("total", nvl(m.getTotalTaxPaise()));
            r.put("status", m.getStatus());
            b.row(r);
            running += nvl(m.getTotalTaxPaise());
        }
        b.summary("Movements", all.size());
        b.summary("Net tax (Rs)", ReportValues.rupees(running));
        return b.build();
    }

    private GstReport hsnSummary(Long orgId, String period) {
        Map<String, long[]> agg = new LinkedHashMap<>();   // hsn -> [qty, taxable, cgst, sgst, igst]
        for (GstMovementLedgerEntity m : movements(orgId, period)) {
            if (!"OUT".equals(m.getDirection())) continue;
            String hsn = m.getHsnCode() != null ? m.getHsnCode() : "UNCLASSIFIED";
            long[] a = agg.computeIfAbsent(hsn, k -> new long[5]);
            a[0] += m.getQuantity() != null ? m.getQuantity() : 0;
            a[1] += nvl(m.getTaxableValuePaise());
            a[2] += nvl(m.getCgstAmountPaise());
            a[3] += nvl(m.getSgstAmountPaise());
            a[4] += nvl(m.getIgstAmountPaise());
        }
        var b = GstReport.of(HSN_SUMMARY, "HSN Summary").period(period)
                .column(text("hsn", "HSN"))
                .column(number("quantity", "Qty"))
                .column(money("taxable", "Taxable"))
                .column(money("cgst", "CGST"))
                .column(money("sgst", "SGST"))
                .column(money("igst", "IGST"))
                .column(money("total", "Total Tax"));
        long taxable = 0, cgst = 0, sgst = 0, igst = 0;
        for (var e : agg.entrySet()) {
            long[] a = e.getValue();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("hsn", e.getKey());
            r.put("quantity", a[0]);
            r.put("taxable", a[1]);
            r.put("cgst", a[2]); r.put("sgst", a[3]); r.put("igst", a[4]);
            r.put("total", a[2] + a[3] + a[4]);
            b.row(r);
            taxable += a[1]; cgst += a[2]; sgst += a[3]; igst += a[4];
        }
        return totals(b, taxable, cgst, sgst, igst).build();
    }

    // ── Purchase and ITC ─────────────────────────────────────────────────────

    private List<GstInputTaxEntity> inputs(Long orgId, String period) {
        return inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(period).stream()
                .filter(i -> orgId == null || orgId.equals(i.getOrganizationId()))
                .toList();
    }

    private GstReport purchase(Long orgId, String period) {
        var b = GstReport.of(PURCHASE, "GST Purchase Report").period(period)
                .column(text("vendor", "Vendor"))
                .column(text("vendorGstin", "Vendor GSTIN"))
                .column(text("invoice", "Invoice"))
                .column(date("date", "Date"))
                .column(money("taxable", "Taxable"))
                .column(money("cgst", "CGST"))
                .column(money("sgst", "SGST"))
                .column(money("igst", "IGST"))
                .column(money("total", "Total Tax"))
                .column(text("itcEligibility", "ITC Eligibility"))
                .column(text("gstr2b", "2B Status"));

        long taxable = 0, cgst = 0, sgst = 0, igst = 0;
        for (GstInputTaxEntity i : inputs(orgId, period)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("vendor", i.getVendorName());
            r.put("vendorGstin", i.getVendorGstin());
            r.put("invoice", i.getInvoiceNumber());
            r.put("date", String.valueOf(i.getInvoiceDate()));
            r.put("taxable", nvl(i.getTaxableValue()));
            r.put("cgst", nvl(i.getCgstAmount()));
            r.put("sgst", nvl(i.getSgstAmount()));
            r.put("igst", nvl(i.getIgstAmount()));
            r.put("total", nvl(i.getTotalInputTax()));
            r.put("itcEligibility", i.getItcEligibility());
            r.put("gstr2b", i.getGstr2bMatchStatus());
            b.row(r);
            taxable += nvl(i.getTaxableValue()); cgst += nvl(i.getCgstAmount());
            sgst += nvl(i.getSgstAmount()); igst += nvl(i.getIgstAmount());
        }
        return totals(b, taxable, cgst, sgst, igst).build();
    }

    private GstReport inputTax(Long orgId, String period) {
        var b = GstReport.of(INPUT_TAX, "Input Tax and ITC Report").period(period)
                .column(text("vendor", "Vendor"))
                .column(text("invoice", "Invoice"))
                .column(money("inputTax", "Input Tax"))
                .column(money("eligible", "Eligible"))
                .column(money("ineligible", "Ineligible"))
                .column(money("claimed", "Claimed"))
                .column(money("reversed", "Reversed"))
                .column(money("reclaimed", "Reclaimed"))
                .column(money("net", "Net ITC"))
                .column(text("status", "Status"))
                .column(text("reason", "Reason"));

        long input = 0, eligible = 0, claimed = 0, reversed = 0, reclaimed = 0;
        for (GstInputTaxEntity i : inputs(orgId, period)) {
            long elig = nvl(i.getEligibleCgst()) + nvl(i.getEligibleSgst()) + nvl(i.getEligibleIgst());
            long inel = nvl(i.getIneligibleCgst()) + nvl(i.getIneligibleSgst()) + nvl(i.getIneligibleIgst());
            long clm  = nvl(i.getClaimedCgst()) + nvl(i.getClaimedSgst()) + nvl(i.getClaimedIgst());
            long rev  = nvl(i.getReversedCgst()) + nvl(i.getReversedSgst()) + nvl(i.getReversedIgst());
            long rec  = nvl(i.getReclaimedCgst()) + nvl(i.getReclaimedSgst()) + nvl(i.getReclaimedIgst());

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("vendor", i.getVendorName());
            r.put("invoice", i.getInvoiceNumber());
            r.put("inputTax", nvl(i.getTotalInputTax()));
            r.put("eligible", elig);
            r.put("ineligible", inel);
            r.put("claimed", clm);
            r.put("reversed", rev);
            r.put("reclaimed", rec);
            r.put("net", clm - rev + rec);
            r.put("status", i.getItcStatus());
            r.put("reason", i.getEligibilityReason());
            b.row(r);
            input += nvl(i.getTotalInputTax()); eligible += elig;
            claimed += clm; reversed += rev; reclaimed += rec;
        }
        b.summary("Total input tax (Rs)", ReportValues.rupees(input));
        b.summary("Eligible ITC (Rs)", ReportValues.rupees(eligible));
        b.summary("Claimed (Rs)", ReportValues.rupees(claimed));
        b.summary("Reversed (Rs)", ReportValues.rupees(reversed));
        b.summary("Reclaimed (Rs)", ReportValues.rupees(reclaimed));
        b.summary("Net ITC (Rs)", ReportValues.rupees(claimed - reversed + reclaimed));
        return b.build();
    }

    private GstReport gstr2bRecon(Long orgId, String period) {
        var b = GstReport.of(GSTR2B_RECON, "GSTR-2B Reconciliation").period(period)
                .column(text("invoice", "Purchase Invoice"))
                .column(text("status", "Match Status"))
                .column(money("taxableDiff", "Taxable Diff"))
                .column(money("cgstDiff", "CGST Diff"))
                .column(money("sgstDiff", "SGST Diff"))
                .column(money("igstDiff", "IGST Diff"))
                .column(money("totalDiff", "Total Diff"))
                .column(text("notes", "Notes"));

        Map<String, Integer> counts = new LinkedHashMap<>();
        long totalDiff = 0;
        for (ItcReconciliationEntity e : reconRepo.findByOrganizationIdAndTaxPeriodOrderByIdAsc(orgId, period)) {
            String invoice = inputTaxRepo.findById(e.getInputTaxId() == null ? -1L : e.getInputTaxId())
                    .map(GstInputTaxEntity::getInvoiceNumber).orElse("—");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("invoice", invoice);
            r.put("status", e.getMatchStatus());
            r.put("taxableDiff", nvl(e.getTaxableDifferencePaise()));
            r.put("cgstDiff", nvl(e.getCgstDifferencePaise()));
            r.put("sgstDiff", nvl(e.getSgstDifferencePaise()));
            r.put("igstDiff", nvl(e.getIgstDifferencePaise()));
            r.put("totalDiff", nvl(e.getTotalDifferencePaise()));
            r.put("notes", e.getDifferenceNotes());
            b.row(r);
            counts.merge(e.getMatchStatus(), 1, Integer::sum);
            totalDiff += Math.abs(nvl(e.getTotalDifferencePaise()));
        }
        counts.forEach((k, v) -> b.summary(k, v));
        b.summary("Absolute difference (Rs)", ReportValues.rupees(totalDiff));
        return b.build();
    }

    // ── Documents ────────────────────────────────────────────────────────────

    private GstReport creditNotes(Long orgId, String period) {
        var b = GstReport.of(CREDIT_NOTE, "Credit Note Report").period(period)
                .column(text("number", "Credit Note"))
                .column(date("date", "Date"))
                .column(text("originalInvoice", "Original Order"))
                .column(text("originalPeriod", "Original Period"))
                .column(text("customer", "Customer"))
                .column(money("taxable", "Taxable"))
                .column(money("cgst", "CGST"))
                .column(money("sgst", "SGST"))
                .column(money("igst", "IGST"))
                .column(money("total", "Total"))
                .column(text("status", "Status"));

        long taxable = 0, cgst = 0, sgst = 0, igst = 0;
        for (GstCreditNoteEntity c : creditNoteRepo.findByTaxPeriodOrderByCreatedAtDesc(period)) {
            if (orgId != null && c.getOrganizationId() != null && !orgId.equals(c.getOrganizationId())) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("number", c.getCreditNoteNumber());
            r.put("date", String.valueOf(c.getCreditNoteDate()));
            r.put("originalInvoice", c.getOriginalOrderCode());
            r.put("originalPeriod", c.getOriginalTaxPeriod());
            r.put("customer", c.getCustomerName());
            r.put("taxable", nvl(c.getTaxableValuePaise()));
            r.put("cgst", nvl(c.getCgstPaise()));
            r.put("sgst", nvl(c.getSgstPaise()));
            r.put("igst", nvl(c.getIgstPaise()));
            r.put("total", nvl(c.getTotalCreditPaise()));
            r.put("status", c.getStatus());
            b.row(r);
            taxable += nvl(c.getTaxableValuePaise()); cgst += nvl(c.getCgstPaise());
            sgst += nvl(c.getSgstPaise()); igst += nvl(c.getIgstPaise());
        }
        return totals(b, taxable, cgst, sgst, igst).build();
    }

    private GstReport debitNotes(Long orgId, String period) {
        var b = GstReport.of(DEBIT_NOTE, "Debit Note Report").period(period)
                .column(text("number", "Debit Note"))
                .column(date("date", "Date"))
                .column(text("originalInvoice", "Original Order"))
                .column(text("customer", "Customer"))
                .column(money("taxable", "Taxable"))
                .column(money("cgst", "CGST"))
                .column(money("sgst", "SGST"))
                .column(money("igst", "IGST"))
                .column(money("total", "Total"))
                .column(text("status", "Status"));

        long taxable = 0, cgst = 0, sgst = 0, igst = 0;
        for (GstDebitNoteEntity d : debitNoteRepo.findByOrganizationIdAndTaxPeriodOrderByIdAsc(orgId, period)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("number", d.getDebitNoteNumber());
            r.put("date", String.valueOf(d.getDebitNoteDate()));
            r.put("originalInvoice", d.getOriginalOrderCode());
            r.put("customer", d.getCustomerName());
            r.put("taxable", nvl(d.getTaxableValuePaise()));
            r.put("cgst", nvl(d.getCgstPaise()));
            r.put("sgst", nvl(d.getSgstPaise()));
            r.put("igst", nvl(d.getIgstPaise()));
            r.put("total", nvl(d.getTotalDebitPaise()));
            r.put("status", d.getStatus());
            b.row(r);
            taxable += nvl(d.getTaxableValuePaise()); cgst += nvl(d.getCgstPaise());
            sgst += nvl(d.getSgstPaise()); igst += nvl(d.getIgstPaise());
        }
        return totals(b, taxable, cgst, sgst, igst).build();
    }

    private GstReport taxPayment(Long orgId, String period) {
        var b = GstReport.of(TAX_PAYMENT, "Tax Payment Report").period(period)
                .column(text("reference", "Reference"))
                .column(date("date", "Date"))
                .column(text("challan", "Challan"))
                .column(money("cgst", "CGST"))
                .column(money("sgst", "SGST"))
                .column(money("igst", "IGST"))
                .column(money("interest", "Interest"))
                .column(money("lateFee", "Late Fee"))
                .column(money("total", "Total Paid"));

        long total = 0;
        for (GstPaymentEntity p : paymentRepo.findByOrganizationIdAndTaxPeriodOrderByIdAsc(orgId, period)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("reference", p.getPaymentReference());
            r.put("date", String.valueOf(p.getPaymentDate()));
            r.put("challan", p.getChallanNumber());
            r.put("cgst", nvl(p.getCgstPaise()));
            r.put("sgst", nvl(p.getSgstPaise()));
            r.put("igst", nvl(p.getIgstPaise()));
            r.put("interest", nvl(p.getInterestPaise()));
            r.put("lateFee", nvl(p.getLateFeePaise()));
            r.put("total", nvl(p.getTotalPaise()));
            b.row(r);
            total += nvl(p.getTotalPaise());
        }
        b.summary("Total paid (Rs)", ReportValues.rupees(total));
        return b.build();
    }

    // ── Period and return datasets ───────────────────────────────────────────

    private GstReport periodSummary(Long orgId) {
        var b = GstReport.of(PERIOD_SUMMARY, "Tax Period Summary")
                .column(text("period", "Period"))
                .column(text("status", "Status"))
                .column(money("outputTax", "Output Tax"))
                .column(money("inputTax", "Input Tax"))
                .column(money("eligibleItc", "Eligible ITC"))
                .column(money("cashPayable", "Cash Payable"))
                .column(text("gstr1", "GSTR-1"))
                .column(text("gstr3b", "GSTR-3B"))
                .column(text("gstr2b", "GSTR-2B"));

        for (GstTaxPeriodEntity p : periodRepo.findByOrganizationIdOrderByTaxPeriodDesc(orgId)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("period", p.getTaxPeriod());
            r.put("status", p.getStatus());
            r.put("outputTax", nvl(p.getTotalOutputTax()));
            r.put("inputTax", nvl(p.getTotalInputTax()));
            r.put("eligibleItc", nvl(p.getEligibleCgstItc()) + nvl(p.getEligibleSgstItc()) + nvl(p.getEligibleIgstItc()));
            r.put("cashPayable", nvl(p.getCashCgstPayable()) + nvl(p.getCashSgstPayable()));
            r.put("gstr1", p.getGstr1Status());
            r.put("gstr3b", p.getGstr3bStatus());
            r.put("gstr2b", p.getGstr2bStatus());
            b.row(r);
        }
        return b.build();
    }

    /** GSTR-1 as a flat dataset. The preparation engine remains the source. */
    private GstReport gstr1(Long orgId, String period) {
        var b = GstReport.of(GSTR1, "GSTR-1 Preparation").period(period)
                .meta("disclaimer", "Internal preparation dataset. Not the official GSTR-1 filing format and not filed.")
                .column(text("section", "Section"))
                .column(text("document", "Document"))
                .column(date("date", "Date"))
                .column(text("counterparty", "Counterparty"))
                .column(text("gstin", "GSTIN"))
                .column(text("pos", "POS"))
                .column(text("hsn", "HSN"))
                .column(money("taxable", "Taxable"))
                .column(money("cgst", "CGST"))
                .column(money("sgst", "SGST"))
                .column(money("igst", "IGST"));

        for (GstMovementLedgerEntity m : movements(orgId, period)) {
            if (!GstMovementService.SALE_OUTPUT.equals(m.getMovementType())) continue;
            boolean b2b = m.getCounterpartyGstin() != null && !m.getCounterpartyGstin().isBlank();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("section", b2b ? "B2B" : "B2C");
            r.put("document", m.getSourceDocumentNumber());
            r.put("date", String.valueOf(m.getTransactionDate()));
            r.put("counterparty", m.getCounterpartyName());
            r.put("gstin", m.getCounterpartyGstin());
            r.put("pos", m.getPlaceOfSupply());
            r.put("hsn", m.getHsnCode());
            r.put("taxable", nvl(m.getTaxableValuePaise()));
            r.put("cgst", nvl(m.getCgstAmountPaise()));
            r.put("sgst", nvl(m.getSgstAmountPaise()));
            r.put("igst", nvl(m.getIgstAmountPaise()));
            b.row(r);
        }
        for (GstCreditNoteEntity c : creditNoteRepo.findByTaxPeriodOrderByCreatedAtDesc(period)) {
            if (orgId != null && c.getOrganizationId() != null && !orgId.equals(c.getOrganizationId())) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("section", "CREDIT_NOTE");
            r.put("document", c.getCreditNoteNumber());
            r.put("date", String.valueOf(c.getCreditNoteDate()));
            r.put("counterparty", c.getCustomerName());
            r.put("gstin", c.getCustomerGstin());
            r.put("pos", c.getPlaceOfSupply());
            r.put("hsn", "");
            r.put("taxable", -nvl(c.getTaxableValuePaise()));
            r.put("cgst", -nvl(c.getCgstPaise()));
            r.put("sgst", -nvl(c.getSgstPaise()));
            r.put("igst", -nvl(c.getIgstPaise()));
            b.row(r);
        }
        return b.build();
    }

    private GstReport gstr3b(Long orgId, String period) {
        GstTaxPeriodEntity p = periodService.recompute(period);
        long[] claim   = itcSum(orgId, "CLAIM", period);
        long[] reverse = itcSum(orgId, "REVERSAL", period);
        long[] reclaim = itcSum(orgId, "RECLAIM", period);

        var b = GstReport.of(GSTR3B, "GSTR-3B Preparation").period(period)
                .meta("disclaimer", "Internal preparation dataset. Not the official GSTR-3B filing format and not filed.")
                .column(text("line", "Line"))
                .column(money("cgst", "CGST"))
                .column(money("sgst", "SGST"))
                .column(money("igst", "IGST"))
                .column(money("total", "Total"));

        b.row(line("Outward taxable supplies", nvl(p.getOutputCgst()), nvl(p.getOutputSgst()), nvl(p.getOutputIgst())));
        b.row(line("Eligible ITC", nvl(p.getEligibleCgstItc()), nvl(p.getEligibleSgstItc()), nvl(p.getEligibleIgstItc())));
        b.row(line("ITC claimed", claim[0], claim[1], claim[2]));
        b.row(line("ITC reversed", -reverse[0], -reverse[1], -reverse[2]));
        b.row(line("ITC reclaimed", reclaim[0], reclaim[1], reclaim[2]));
        b.row(line("Net ITC", claim[0] - reverse[0] + reclaim[0],
                              claim[1] - reverse[1] + reclaim[1],
                              claim[2] - reverse[2] + reclaim[2]));
        b.row(line("Net liability", nvl(p.getNetCgstLiability()), nvl(p.getNetSgstLiability()), nvl(p.getNetIgstLiability())));
        b.row(line("Cash payable", nvl(p.getCashCgstPayable()), nvl(p.getCashSgstPayable()), 0L));

        long[] paid = paymentTotals(orgId, period);
        b.row(line("Tax paid", paid[0], paid[1], paid[2]));
        b.summary("Interest paid (Rs)", ReportValues.rupees(paid[3]));
        b.summary("Late fee paid (Rs)", ReportValues.rupees(paid[4]));
        b.summary("Period status", p.getStatus());
        return b.build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> line(String label, long cgst, long sgst, long igst) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("line", label);
        r.put("cgst", cgst); r.put("sgst", sgst); r.put("igst", igst);
        r.put("total", cgst + sgst + igst);
        return r;
    }

    private GstReport.Builder totals(GstReport.Builder b, long taxable, long cgst, long sgst, long igst) {
        b.summary("Taxable value (Rs)", ReportValues.rupees(taxable));
        b.summary("CGST (Rs)", ReportValues.rupees(cgst));
        b.summary("SGST (Rs)", ReportValues.rupees(sgst));
        b.summary("IGST (Rs)", ReportValues.rupees(igst));
        b.summary("Total tax (Rs)", ReportValues.rupees(cgst + sgst + igst));
        return b;
    }

    private long[] itcSum(Long orgId, String type, String period) {
        List<Object[]> rows = itcTxnRepo.sumByType(orgId, type, period);
        if (rows.isEmpty() || rows.get(0) == null) return new long[]{0, 0, 0, 0};
        Object[] r = rows.get(0);
        return new long[]{ toLong(r[0]), toLong(r[1]), toLong(r[2]), toLong(r[3]) };
    }

    private long[] paymentTotals(Long orgId, String period) {
        List<Object[]> rows = paymentRepo.sumForPeriod(orgId, period);
        if (rows.isEmpty() || rows.get(0) == null) return new long[]{0, 0, 0, 0, 0, 0};
        Object[] r = rows.get(0);
        return new long[]{ toLong(r[0]), toLong(r[1]), toLong(r[2]),
                           toLong(r[3]), toLong(r[4]), toLong(r[5]) };
    }

    private static long nvl(Long v) { return v != null ? v : 0L; }
    private static long toLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
