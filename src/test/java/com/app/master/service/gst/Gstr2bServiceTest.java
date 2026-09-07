package com.app.master.service.gst;

import com.app.master.service.core.entity.GstInputTaxEntity;
import com.app.master.service.core.entity.Gstr2bImportEntity;
import com.app.master.service.core.entity.Gstr2bRecordEntity;
import com.app.master.service.core.entity.ItcReconciliationEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.*;
import com.app.master.service.service.admin.gstr2b.Gstr2bNormalizer;
import com.app.master.service.service.admin.gstr2b.Gstr2bParserRegistry;
import com.app.master.service.service.admin.gstr2b.Gstr2bParserV1;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.app.master.service.service.admin.Gstr2bService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * GSTR-2B ingestion and matching (spec sections 9, 10 and 24).
 *
 * The central rule under test: a difference is never silently reported as
 * MATCHED.
 */
class Gstr2bServiceTest {

    private Gstr2bImportRepository importRepo;
    private Gstr2bRecordRepository recordRepo;
    private ItcReconciliationRepository reconRepo;
    private GstInputTaxRepository inputTaxRepo;
    private Gstr2bService service;
    private Gstr2bParserRegistry parserRegistry;

    private final List<Gstr2bRecordEntity> records = new ArrayList<>();
    private final List<ItcReconciliationEntity> recons = new ArrayList<>();

    private static final String PERIOD = "2026-09";
    private static final String VENDOR = "21AAAAA0000A1Z5";

    @BeforeEach
    void setUp() {
        importRepo = mock(Gstr2bImportRepository.class);
        recordRepo = mock(Gstr2bRecordRepository.class);
        reconRepo = mock(ItcReconciliationRepository.class);
        inputTaxRepo = mock(GstInputTaxRepository.class);
        GstTaxPeriodRepository periodRepo = mock(GstTaxPeriodRepository.class);
        GstIdentityService identity = mock(GstIdentityService.class);
        GstAuditService audit = mock(GstAuditService.class);
        GstSecurityContext ctx = mock(GstSecurityContext.class);

        when(ctx.current()).thenReturn(Optional.empty());
        when(ctx.actor()).thenReturn("tester");
        when(identity.defaultOrganizationId()).thenReturn(1L);
        when(identity.primaryRegistration(any())).thenReturn(Optional.empty());
        when(periodRepo.findFirstByOrganizationIdAndTaxPeriod(any(), anyString()))
                .thenReturn(Optional.empty());

        records.clear();
        recons.clear();

        AtomicLong ids = new AtomicLong(10);
        when(importRepo.save(any())).thenAnswer(i -> {
            Gstr2bImportEntity e = i.getArgument(0);
            if (e.getId() == null) e.setId(ids.incrementAndGet());
            return e;
        });
        AtomicLong rids = new AtomicLong(100);
        when(recordRepo.save(any())).thenAnswer(i -> {
            Gstr2bRecordEntity r = i.getArgument(0);
            if (r.getId() == null) { r.setId(rids.incrementAndGet()); records.add(r); }
            return r;
        });
        when(reconRepo.save(any())).thenAnswer(i -> {
            ItcReconciliationEntity r = i.getArgument(0);
            recons.add(r);
            return r;
        });
        when(inputTaxRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(recordRepo.findByOrganizationIdAndTaxPeriodOrderByIdAsc(any(), anyString()))
                .thenAnswer(i -> new ArrayList<>(records));

        GstRoundingService rounding = new GstRoundingService();
        parserRegistry = new Gstr2bParserRegistry(
                List.of(new Gstr2bParserV1(new Gstr2bNormalizer(rounding))),
                new com.fasterxml.jackson.databind.ObjectMapper());
        service = new Gstr2bService(importRepo, recordRepo, reconRepo, inputTaxRepo, periodRepo,
                identity, audit, rounding, ctx, parserRegistry);
    }

    private GstInputTaxEntity purchase(String invoiceNo, long taxable, long cgst, long sgst) {
        return GstInputTaxEntity.builder()
                .id(1L).invoiceNumber(invoiceNo).vendorGstin(VENDOR)
                .invoiceDate(LocalDate.of(2026, 9, 10))
                .taxPeriod(PERIOD).organizationId(1L)
                .taxableValue(taxable).cgstAmount(cgst).sgstAmount(sgst).igstAmount(0L)
                .totalInputTax(cgst + sgst)
                .build();
    }

    private void given2b(String csv) throws Exception {
        service.importFile(PERIOD, "2b.csv", "CSV", csv.getBytes(StandardCharsets.UTF_8));
    }

    private static final String HEADER =
            "supplier_gstin,supplier_name,invoice_number,invoice_date,taxable_value,cgst,sgst,igst,cess\n";

    // ── Import ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CSV import parses rupees into paise")
    void csvImportParsesMoney() throws Exception {
        Gstr2bImportEntity imp = service.importFile(PERIOD, "2b.csv", "CSV",
                (HEADER + VENDOR + ",Acme,INV-1,2026-09-10,10000.00,250.00,250.00,0,0\n")
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(1, imp.getRecordCount());
        assertEquals(1000000L, imp.getTotalTaxablePaise(), "₹10,000 is 10,00,000 paise");
        assertEquals(50000L, imp.getTotalTaxPaise());
        assertEquals(25000L, records.get(0).getCgstPaise());
    }

    @Test
    @DisplayName("JSON import accepts both friendly and portal field names")
    void jsonImportAcceptsBothNamings() throws Exception {
        String json = "[{\"ctin\":\"" + VENDOR + "\",\"inum\":\"INV-9\",\"idt\":\"10-09-2026\","
                + "\"txval\":\"5000\",\"camt\":\"125\",\"samt\":\"125\",\"iamt\":\"0\"}]";
        Gstr2bImportEntity imp = service.importFile(PERIOD, "2b.json", "JSON",
                json.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, imp.getRecordCount());
        assertEquals(500000L, imp.getTotalTaxablePaise());
        assertEquals(LocalDate.of(2026, 9, 10), records.get(0).getInvoiceDate());
    }

    @Test
    @DisplayName("An unsupported format is refused with a clear message")
    void unsupportedFormatRefused() {
        VeloriaException ex = assertThrows(VeloriaException.class,
                () -> service.importFile(PERIOD, "2b.xlsx", "XLSX", new byte[]{1}));
        assertTrue(ex.getMessage().contains("Supported: CSV, JSON"), ex.getMessage());
    }

    @Test
    @DisplayName("A file missing required columns is refused")
    void missingColumnsRefused() {
        VeloriaException ex = assertThrows(VeloriaException.class,
                () -> service.importFile(PERIOD, "bad.csv", "CSV",
                        "foo,bar\n1,2\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("missing required column"), ex.getMessage());
    }

    @Test
    @DisplayName("A duplicate supplier invoice in 2B is flagged, not merged")
    void duplicateIn2bIsFlagged() throws Exception {
        given2b(HEADER
                + VENDOR + ",Acme,INV-1,2026-09-10,10000,250,250,0,0\n"
                + VENDOR + ",Acme,INV-1,2026-09-10,10000,250,250,0,0\n");

        assertEquals(2, records.size());
        assertEquals(1, records.stream().filter(r -> DUPLICATE.equals(r.getMatchStatus())).count());
    }

    // ── Reconciliation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Identical register and 2B amounts produce MATCHED")
    void exactMatch() throws Exception {
        given2b(HEADER + VENDOR + ",Acme,INV-1,2026-09-10,10000,250,250,0,0\n");
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD))
                .thenReturn(List.of(purchase("INV-1", 1000000L, 25000L, 25000L)));

        ReconResult r = service.reconcile(PERIOD);

        assertEquals(1, r.matched());
        assertEquals(0, r.mismatch());
        assertEquals(0L, r.totalDifferencePaise());
    }

    @Test
    @DisplayName("A tax difference is MISMATCH with the delta recorded, never silently matched")
    void taxDifferenceIsMismatch() throws Exception {
        // Register says ₹500 tax; 2B says ₹450. The spec's worked example.
        given2b(HEADER + VENDOR + ",Acme,INV-1,2026-09-10,10000,225,225,0,0\n");
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD))
                .thenReturn(List.of(purchase("INV-1", 1000000L, 25000L, 25000L)));

        ReconResult r = service.reconcile(PERIOD);

        assertEquals(0, r.matched(), "A difference must never be reported as matched");
        assertEquals(1, r.mismatch());
        assertEquals(5000L, r.totalDifferencePaise(), "₹50 difference in paise");

        ItcReconciliationEntity recon = recons.get(0);
        assertEquals(MISMATCH, recon.getMatchStatus());
        assertEquals(2500L, recon.getCgstDifferencePaise());
        assertTrue(recon.getDifferenceNotes().contains("CGST"), recon.getDifferenceNotes());
    }

    @Test
    @DisplayName("A one-paise difference still counts as a mismatch")
    void onePaiseIsStillAMismatch() throws Exception {
        given2b(HEADER + VENDOR + ",Acme,INV-1,2026-09-10,10000,249.99,250,0,0\n");
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD))
                .thenReturn(List.of(purchase("INV-1", 1000000L, 25000L, 25000L)));

        assertEquals(1, service.reconcile(PERIOD).mismatch());
    }

    @Test
    @DisplayName("Matching amounts with a different date are PARTIAL_MATCH")
    void dateOnlyDifferenceIsPartial() throws Exception {
        given2b(HEADER + VENDOR + ",Acme,INV-1,2026-09-15,10000,250,250,0,0\n");
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD))
                .thenReturn(List.of(purchase("INV-1", 1000000L, 25000L, 25000L)));

        ReconResult r = service.reconcile(PERIOD);
        assertEquals(1, r.partialMatch());
        assertEquals(0, r.matched());
        assertTrue(recons.get(0).getDifferenceNotes().contains("date"), recons.get(0).getDifferenceNotes());
    }

    @Test
    @DisplayName("A purchase absent from 2B is MISSING_IN_2B")
    void missingIn2b() throws Exception {
        given2b(HEADER + VENDOR + ",Acme,OTHER-1,2026-09-10,10000,250,250,0,0\n");
        GstInputTaxEntity p = purchase("INV-1", 1000000L, 25000L, 25000L);
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(p));

        ReconResult r = service.reconcile(PERIOD);

        assertEquals(1, r.missingIn2b());
        assertEquals(MISSING_IN_2B, p.getGstr2bMatchStatus());
    }

    @Test
    @DisplayName("A 2B line with no purchase behind it is reported")
    void in2bNotInRegister() throws Exception {
        given2b(HEADER + VENDOR + ",Acme,GHOST-1,2026-09-10,10000,250,250,0,0\n");
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of());

        ReconResult r = service.reconcile(PERIOD);

        assertTrue(r.differences().stream()
                        .anyMatch(d -> "IN_2B_NOT_IN_REGISTER".equals(d.get("type"))),
                "A 2B invoice with no purchase record must be surfaced");
    }

    @Test
    @DisplayName("Invoice numbers match despite formatting differences")
    void invoiceNumberNormalised() throws Exception {
        given2b(HEADER + VENDOR + ",Acme,inv/001,2026-09-10,10000,250,250,0,0\n");
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD))
                .thenReturn(List.of(purchase("INV-001", 1000000L, 25000L, 25000L)));

        assertEquals(1, service.reconcile(PERIOD).matched());
    }

    @Test
    @DisplayName("The match status is written back onto the purchase invoice")
    void statusWrittenBackToPurchase() throws Exception {
        given2b(HEADER + VENDOR + ",Acme,INV-1,2026-09-10,10000,250,250,0,0\n");
        GstInputTaxEntity p = purchase("INV-1", 1000000L, 25000L, 25000L);
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(p));

        service.reconcile(PERIOD);

        assertEquals(MATCHED, p.getGstr2bMatchStatus());
        verify(inputTaxRepo, atLeastOnce()).save(p);
    }

    @Test
    @DisplayName("Reconciliation is repeatable and clears the previous run")
    void reconciliationIsRepeatable() throws Exception {
        given2b(HEADER + VENDOR + ",Acme,INV-1,2026-09-10,10000,250,250,0,0\n");
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD))
                .thenReturn(List.of(purchase("INV-1", 1000000L, 25000L, 25000L)));

        service.reconcile(PERIOD);
        service.reconcile(PERIOD);

        verify(reconRepo, times(2)).deleteByOrganizationIdAndTaxPeriod(any(), eq(PERIOD));
    }

    // ── Nested government GSTR-2B JSON (phase 2/3) ───────────────────────────

    /** Minimal well-formed government payload with one supplier and one invoice. */
    private String govJson(String body) {
        return "{\"data\":{\"gstin\":\"21AABCU9603R1ZX\",\"rtnprd\":\"092026\","
             + "\"version\":\"1.0\",\"docdata\":{" + body + "}}}";
    }

    private byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    @DisplayName("Nested government JSON is parsed into the same rows the CSV reader produces")
    void nestedJsonParses() throws Exception {
        String json = govJson("\"b2b\":[{\"ctin\":\"" + VENDOR + "\",\"trdnm\":\"Acme Textiles\","
                + "\"inv\":[{\"inum\":\"INV-1\",\"dt\":\"05-09-2026\",\"typ\":\"R\",\"pos\":\"21\","
                + "\"items\":[{\"num\":1,\"rt\":18,\"txval\":1000,\"cgst\":90,\"sgst\":90,\"igst\":0,\"cess\":0}]}]}]");

        var rows = parserRegistry.parse(bytes(json));

        assertEquals(1, rows.size());
        var r = rows.get(0);
        assertEquals(VENDOR, r.supplierGstin());
        assertEquals("Acme Textiles", r.supplierName());
        assertEquals("INV-1", r.invoiceNumber());
        assertEquals(LocalDate.of(2026, 9, 5), r.invoiceDate());
        assertEquals("INVOICE", r.invoiceType());
        assertEquals(100000L, r.taxablePaise(), "rupees convert to paise");
        assertEquals(9000L, r.cgstPaise());
        assertEquals(9000L, r.sgstPaise());
    }

    @Test
    @DisplayName("Multiple vendors, multiple invoices and multiple line items all come through")
    void nestedJsonMultipleVendorsInvoicesAndItems() throws Exception {
        String json = govJson("\"b2b\":["
                + "{\"ctin\":\"" + VENDOR + "\",\"trdnm\":\"Acme\",\"inv\":["
                + "  {\"inum\":\"A-1\",\"dt\":\"01-09-2026\",\"items\":["
                + "     {\"rt\":18,\"txval\":1000,\"cgst\":90,\"sgst\":90},"
                + "     {\"rt\":5,\"txval\":500,\"cgst\":12.50,\"sgst\":12.50}]},"
                + "  {\"inum\":\"A-2\",\"dt\":\"02-09-2026\",\"items\":[{\"rt\":18,\"txval\":200,\"igst\":36}]}]},"
                + "{\"ctin\":\"27BBBBB1111B1Z5\",\"trdnm\":\"Beta\",\"inv\":["
                + "  {\"inum\":\"B-1\",\"dt\":\"03-09-2026\",\"items\":[{\"rt\":12,\"txval\":800,\"igst\":96}]}]}]");

        var rows = parserRegistry.parse(bytes(json));

        assertEquals(3, rows.size(), "two suppliers, three invoices");
        var a1 = rows.stream().filter(r -> r.invoiceNumber().equals("A-1")).findFirst().orElseThrow();
        assertEquals(150000L, a1.taxablePaise(), "line items are summed");
        assertEquals(10250L, a1.cgstPaise(), "9000 + 1250 paise");
        var b1 = rows.stream().filter(r -> r.invoiceNumber().equals("B-1")).findFirst().orElseThrow();
        assertEquals(9600L, b1.igstPaise());
    }

    @Test
    @DisplayName("Credit and debit notes are typed from the note flag")
    void nestedJsonCreditAndDebitNotes() throws Exception {
        String json = govJson("\"cdnr\":[{\"ctin\":\"" + VENDOR + "\",\"trdnm\":\"Acme\",\"nt\":["
                + "{\"ntnum\":\"CN-1\",\"typ\":\"C\",\"dt\":\"06-09-2026\",\"items\":[{\"txval\":100,\"igst\":18}]},"
                + "{\"ntnum\":\"DN-1\",\"typ\":\"D\",\"dt\":\"07-09-2026\",\"items\":[{\"txval\":50,\"igst\":9}]}]}]");

        var rows = parserRegistry.parse(bytes(json));

        assertEquals(2, rows.size());
        assertEquals("CREDIT_NOTE", rows.stream().filter(r -> r.invoiceNumber().equals("CN-1"))
                .findFirst().orElseThrow().invoiceType());
        assertEquals("DEBIT_NOTE", rows.stream().filter(r -> r.invoiceNumber().equals("DN-1"))
                .findFirst().orElseThrow().invoiceType());
    }

    @Test
    @DisplayName("Invoice and note sections in one payload are both read")
    void nestedJsonMixedSections() throws Exception {
        String json = govJson(
                  "\"b2b\":[{\"ctin\":\"" + VENDOR + "\",\"inv\":[{\"inum\":\"I-1\",\"dt\":\"01-09-2026\","
                + "\"items\":[{\"txval\":100,\"igst\":18}]}]}],"
                + "\"cdnr\":[{\"ctin\":\"" + VENDOR + "\",\"nt\":[{\"ntnum\":\"C-1\",\"typ\":\"C\","
                + "\"dt\":\"02-09-2026\",\"items\":[{\"txval\":10,\"igst\":1.80}]}]}]");

        var rows = parserRegistry.parse(bytes(json));
        assertEquals(2, rows.size());
        assertEquals(180L, rows.stream().filter(r -> r.invoiceNumber().equals("C-1"))
                .findFirst().orElseThrow().igstPaise());
    }

    @Test
    @DisplayName("Amendment sections are read like their originals")
    void nestedJsonAmendmentSections() throws Exception {
        String json = govJson("\"b2ba\":[{\"ctin\":\"" + VENDOR + "\",\"inv\":[{\"inum\":\"AM-1\","
                + "\"dt\":\"04-09-2026\",\"items\":[{\"txval\":300,\"igst\":54}]}]}]");

        var rows = parserRegistry.parse(bytes(json));
        assertEquals(1, rows.size());
        assertEquals(30000L, rows.get(0).taxablePaise());
    }

    @Test
    @DisplayName("A section this parser cannot read is refused, never silently dropped")
    void nestedJsonUnknownSectionIsRefused() {
        String json = govJson("\"b2b\":[{\"ctin\":\"" + VENDOR + "\",\"inv\":[{\"inum\":\"I-1\","
                + "\"dt\":\"01-09-2026\",\"items\":[{\"txval\":100,\"igst\":18}]}]}],"
                + "\"impg\":[{\"boe\":\"1234567\"}]");

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> parserRegistry.parse(bytes(json)));
        assertTrue(e.getMessage().contains("impg"), "the unreadable section must be named");
        assertTrue(e.getMessage().contains("understate"), "and the reason explained");
    }

    @Test
    @DisplayName("An empty unknown section is harmless and does not block the import")
    void nestedJsonEmptyUnknownSectionIsAllowed() throws Exception {
        String json = govJson("\"b2b\":[{\"ctin\":\"" + VENDOR + "\",\"inv\":[{\"inum\":\"I-1\","
                + "\"dt\":\"01-09-2026\",\"items\":[{\"txval\":100,\"igst\":18}]}]}],\"impg\":[]");

        assertEquals(1, parserRegistry.parse(bytes(json)).size());
    }

    @Test
    @DisplayName("An unsupported schema version is refused rather than parsed by old rules")
    void nestedJsonUnsupportedVersionIsRefused() {
        String json = "{\"data\":{\"version\":\"9.9\",\"docdata\":{\"b2b\":[{\"ctin\":\"" + VENDOR
                + "\",\"inv\":[{\"inum\":\"I-1\",\"dt\":\"01-09-2026\","
                + "\"items\":[{\"txval\":100,\"igst\":18}]}]}]}}}";

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> parserRegistry.parse(bytes(json)));
        assertTrue(e.getMessage().contains("9.9"));
        assertTrue(e.getMessage().contains("misreading"), "the risk must be stated");
    }

    @Test
    @DisplayName("A payload matching no known structure is refused with what it actually contained")
    void nestedJsonUnknownStructureIsRefused() {
        VeloriaException e = assertThrows(VeloriaException.class,
                () -> parserRegistry.parse(bytes("{\"something\":\"else\"}")));
        assertTrue(e.getMessage().contains("something"), "report the shape actually seen");
    }

    @Test
    @DisplayName("Invalid JSON is reported as unreadable, not as an empty import")
    void nestedJsonInvalidJson() {
        VeloriaException e = assertThrows(VeloriaException.class,
                () -> parserRegistry.parse(bytes("{ this is not json")));
        assertTrue(e.getMessage().contains("Could not read"));
    }

    @Test
    @DisplayName("A missing supplier GSTIN is refused")
    void nestedJsonMissingGstin() {
        String json = govJson("\"b2b\":[{\"trdnm\":\"No GSTIN\",\"inv\":[{\"inum\":\"I-1\","
                + "\"dt\":\"01-09-2026\",\"items\":[{\"txval\":100,\"igst\":18}]}]}]");

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> parserRegistry.parse(bytes(json)));
        assertTrue(e.getMessage().contains("ctin"));
    }

    @Test
    @DisplayName("A missing invoice number is refused")
    void nestedJsonMissingInvoiceNumber() {
        String json = govJson("\"b2b\":[{\"ctin\":\"" + VENDOR + "\",\"inv\":[{\"dt\":\"01-09-2026\","
                + "\"items\":[{\"txval\":100,\"igst\":18}]}]}]");

        assertThrows(VeloriaException.class, () -> parserRegistry.parse(bytes(json)));
    }

    @Test
    @DisplayName("An unreadable date is refused rather than defaulted to today")
    void nestedJsonBadDate() {
        String json = govJson("\"b2b\":[{\"ctin\":\"" + VENDOR + "\",\"inv\":[{\"inum\":\"I-1\","
                + "\"dt\":\"not-a-date\",\"items\":[{\"txval\":100,\"igst\":18}]}]}]");

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> parserRegistry.parse(bytes(json)));
        assertTrue(e.getMessage().contains("dd-MM-yyyy"), "state the expected format");
    }

    @Test
    @DisplayName("A document with no line items is refused")
    void nestedJsonNoItems() {
        String json = govJson("\"b2b\":[{\"ctin\":\"" + VENDOR + "\",\"inv\":[{\"inum\":\"I-1\","
                + "\"dt\":\"01-09-2026\",\"items\":[]}]}]");

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> parserRegistry.parse(bytes(json)));
        assertTrue(e.getMessage().contains("no line items"));
    }

    @Test
    @DisplayName("An unknown note type is refused rather than assumed to be a credit note")
    void nestedJsonUnknownNoteType() {
        String json = govJson("\"cdnr\":[{\"ctin\":\"" + VENDOR + "\",\"nt\":[{\"ntnum\":\"X-1\","
                + "\"typ\":\"Z\",\"dt\":\"01-09-2026\",\"items\":[{\"txval\":100,\"igst\":18}]}]}]");

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> parserRegistry.parse(bytes(json)));
        assertTrue(e.getMessage().contains("Expected C or D"));
    }

    @Test
    @DisplayName("Nested rows flow through importFile into the existing reconciliation engine")
    void nestedJsonFlowsThroughImportFile() throws Exception {
        String json = govJson("\"b2b\":[{\"ctin\":\"" + VENDOR + "\",\"trdnm\":\"Acme\","
                + "\"inv\":[{\"inum\":\"GOV-1\",\"dt\":\"05-09-2026\","
                + "\"items\":[{\"txval\":1000,\"cgst\":90,\"sgst\":90}]}]}]");

        Gstr2bImportEntity imp = service.importFile(PERIOD, "2b.json", "GOVERNMENT_JSON", bytes(json));

        assertEquals(1, imp.getRecordCount());
        assertEquals("GOVERNMENT_JSON", imp.getSourceFormat());
        assertEquals(100000L, imp.getTotalTaxablePaise());
        assertEquals(18000L, imp.getTotalTaxPaise());
    }

    @Test
    @DisplayName("Documents across multiple tax periods are all parsed; the period is the import's")
    void nestedJsonMultiplePeriods() throws Exception {
        String json = govJson("\"b2b\":[{\"ctin\":\"" + VENDOR + "\",\"inv\":["
                + "{\"inum\":\"P1\",\"dt\":\"31-08-2026\",\"items\":[{\"txval\":100,\"igst\":18}]},"
                + "{\"inum\":\"P2\",\"dt\":\"01-09-2026\",\"items\":[{\"txval\":200,\"igst\":36}]}]}]");

        var rows = parserRegistry.parse(bytes(json));
        assertEquals(2, rows.size());
        assertEquals(LocalDate.of(2026, 8, 31), rows.get(0).invoiceDate());
        assertEquals(LocalDate.of(2026, 9, 1), rows.get(1).invoiceDate());
    }
}
