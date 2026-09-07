package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstInvoiceSeriesEntity;
import com.app.master.service.core.entity.GstRegistrationEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.GstInvoiceSeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Invoice numbering (spec phase 7).
 *
 * Numbers are unique, sequential within a series, and scoped to organization +
 * GST registration + document type + financial year.
 *
 * Concurrency safety has two independent layers:
 *
 *   1. The counter is advanced by {@code UPDATE ... RETURNING}, which holds a
 *      row lock for the statement. Two concurrent callers are serialised and
 *      receive different values. This runs in its own transaction so the number
 *      is consumed even if the caller later rolls back — gaps are acceptable,
 *      duplicates are not.
 *
 *   2. {@code sales_invoice.invoice_number} carries a unique constraint, so a
 *      duplicate that somehow reached the insert fails at the database rather
 *      than being written.
 *
 * Application-level uniqueness alone is deliberately not relied upon.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstInvoiceNumberService {

    public static final String SALES_INVOICE = "SALES_INVOICE";
    public static final String CREDIT_NOTE   = "CREDIT_NOTE";
    public static final String DEBIT_NOTE    = "DEBIT_NOTE";

    private final GstInvoiceSeriesRepository seriesRepo;
    private final GstIdentityService identityService;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    /**
     * Atomically reserves the next number for a series.
     *
     * Run through the EntityManager because Spring Data's {@code @Modifying}
     * cannot return a value, and the returned number is the whole point:
     * UPDATE ... RETURNING holds a row lock for the statement, so two concurrent
     * callers are serialised and receive different numbers.
     */
    private long reserve(Long seriesId) {
        Object result = entityManager.createNativeQuery("""
                UPDATE gst_invoice_series
                   SET next_number = next_number + 1
                 WHERE id = :seriesId
                RETURNING next_number - 1
                """)
                .setParameter("seriesId", seriesId)
                .getSingleResult();
        return ((Number) result).longValue();
    }

    /**
     * Reserves and formats the next number for a series.
     *
     * REQUIRES_NEW: the reservation commits independently, so a caller whose
     * transaction later fails leaves a gap rather than releasing the number for
     * reuse. Reusing a number would risk two invoices sharing it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next(Long organizationId, Long gstRegistrationId,
                       String documentType, LocalDate onDate) throws VeloriaException {

        if (organizationId == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An organization is required to allocate an invoice number");
        }
        String fy = financialYear(onDate != null ? onDate : LocalDate.now());
        GstInvoiceSeriesEntity series = resolveSeries(organizationId, gstRegistrationId, documentType, fy);

        long reserved = reserve(series.getId());
        String number = format(series, reserved);
        log.info("Reserved {} number {} (series {} FY {})", documentType, number, series.getId(), fy);
        return number;
    }

    /** Finds the series for this scope, creating a default one on first use. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GstInvoiceSeriesEntity resolveSeries(Long organizationId, Long gstRegistrationId,
                                                 String documentType, String financialYear) {
        Optional<GstInvoiceSeriesEntity> existing = seriesRepo
                .findByOrganizationIdAndGstRegistrationIdAndDocumentTypeAndFinancialYear(
                        organizationId, gstRegistrationId, documentType, financialYear);
        if (existing.isPresent()) return existing.get();

        String prefix = defaultPrefix(gstRegistrationId, documentType, financialYear);
        try {
            return seriesRepo.save(GstInvoiceSeriesEntity.builder()
                    .organizationId(organizationId)
                    .gstRegistrationId(gstRegistrationId)
                    .documentType(documentType)
                    .financialYear(financialYear)
                    .prefix(prefix)
                    .separator("/")
                    .padding(6)
                    .nextNumber(1L)
                    .active(true)
                    .build());
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // Another request created the series first; use theirs.
            return seriesRepo.findByOrganizationIdAndGstRegistrationIdAndDocumentTypeAndFinancialYear(
                    organizationId, gstRegistrationId, documentType, financialYear)
                    .orElseThrow(() -> raced);
        }
    }

    /**
     * Builds a prefix from the registration's configured document prefix, e.g.
     * {@code FY26-27/INV}. The registration owns the prefix so a second GSTIN
     * gets its own series without code changes.
     */
    private String defaultPrefix(Long gstRegistrationId, String documentType, String fy) {
        String docPrefix = identityService.registration(gstRegistrationId)
                .map(r -> switch (documentType) {
                    case CREDIT_NOTE -> r.getCreditNotePrefix();
                    case DEBIT_NOTE  -> r.getDebitNotePrefix();
                    default          -> r.getInvoicePrefix();
                })
                .filter(p -> p != null && !p.isBlank())
                .orElse(switch (documentType) {
                    case CREDIT_NOTE -> "CN";
                    case DEBIT_NOTE  -> "DN";
                    default          -> "INV";
                });
        return "FY" + fy + "/" + docPrefix;
    }

    private String format(GstInvoiceSeriesEntity series, long number) {
        int padding = series.getPadding() != null ? series.getPadding() : 6;
        String sep = series.getSeparator() != null ? series.getSeparator() : "/";
        String prefix = series.getPrefix() != null ? series.getPrefix() : "INV";
        return prefix + sep + String.format("%0" + padding + "d", number);
    }

    public List<GstInvoiceSeriesEntity> list(Long organizationId) {
        return seriesRepo.findByOrganizationIdOrderByIdAsc(organizationId);
    }

    /** Indian financial year, April to March, rendered as 26-27. */
    public static String financialYear(LocalDate d) {
        int yr = d.getYear();
        return d.getMonthValue() >= 4
                ? String.format("%02d-%02d", yr % 100, (yr + 1) % 100)
                : String.format("%02d-%02d", (yr - 1) % 100, yr % 100);
    }
}
