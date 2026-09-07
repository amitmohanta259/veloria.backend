package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstInputTaxEntity;
import com.app.master.service.core.entity.GstOutputTaxEntity;
import com.app.master.service.repository.admin.GstInputTaxRepository;
import com.app.master.service.repository.admin.GstOutputTaxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GstAccountingService {

    private final GstInputTaxRepository inputTaxRepo;
    private final GstOutputTaxRepository outputTaxRepo;
    private final GstOutputTaxSyncer outputTaxSyncer;

    // ---- Records returned to controller ----

    public record GstSummary(
        String period,
        long salesTaxableValue,
        long outputCgst, long outputSgst, long outputIgst, long totalOutputTax,
        long purchaseTaxableValue,
        long inputCgst, long inputSgst, long inputIgst, long totalInputTax,
        long eligibleItc,
        long netCgstLiability, long netSgstLiability, long netIgstLiability,
        long totalNetLiability,
        long cashCgstPayable, long cashSgstPayable, long cashIgstPayable,
        long totalCashPayable,
        int outputInvoiceCount, int inputInvoiceCount
    ) {}

    public record InputTaxRow(
        Long id, String purchaseOrderUuid, String vendorName, String vendorGstin,
        String invoiceNumber, String invoiceDate, String taxPeriod, String financialYear,
        long taxableValue, long cgstAmount, long sgstAmount, long igstAmount, long totalInputTax,
        String itcEligibility, String itcStatus, String gstr2bMatchStatus, String remarks,
        String gstType
    ) {}

    public record OutputTaxRow(
        Long id, String customerOrderUuid, String orderCode, String customerName,
        String supplyType, String placeOfSupply, String taxPeriod, String financialYear,
        long taxableValue, long cgstAmount, long sgstAmount, long igstAmount, long totalOutputTax,
        long invoiceValue, String invoiceDate, String gstType
    ) {}

    public record LiabilityRow(
        String taxHead, long outputTax, long eligibleItc, long reversals,
        long itcUtilized, long cashPayable
    ) {}

    // ---- Dashboard summary ----

    public GstSummary getSummary(String period) {
        // Output tax — from customer_order (always authoritative)
        outputTaxSyncer.syncForPeriod(period);

        List<GstOutputTaxEntity> outputs = period != null
            ? outputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(period)
            : outputTaxRepo.findAllByOrderByCreatedAtDesc();

        long salesTaxable = outputs.stream().mapToLong(o -> nvl(o.getTaxableValue())).sum();
        long outCgst = outputs.stream().mapToLong(o -> nvl(o.getCgstAmount())).sum();
        long outSgst = outputs.stream().mapToLong(o -> nvl(o.getSgstAmount())).sum();
        long outIgst = outputs.stream().mapToLong(o -> nvl(o.getIgstAmount())).sum();
        long totalOut = outCgst + outSgst + outIgst;

        // Input tax — from uploaded invoices
        List<GstInputTaxEntity> inputs = period != null
            ? inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(period)
            : inputTaxRepo.findAllByOrderByCreatedAtDesc();

        long purchaseTaxable = inputs.stream().mapToLong(i -> nvl(i.getTaxableValue())).sum();
        long inCgst = inputs.stream().mapToLong(i -> nvl(i.getCgstAmount())).sum();
        long inSgst = inputs.stream().mapToLong(i -> nvl(i.getSgstAmount())).sum();
        long inIgst = inputs.stream().mapToLong(i -> nvl(i.getIgstAmount())).sum();
        long totalIn = inCgst + inSgst + inIgst;

        // Only ELIGIBLE ITC counts
        long eligibleItc = inputs.stream()
            .filter(i -> "ELIGIBLE".equals(i.getItcEligibility()))
            .mapToLong(i -> nvl(i.getTotalInputTax())).sum();
        // If nothing marked eligible yet, use total pending as potential
        if (eligibleItc == 0 && totalIn > 0) eligibleItc = totalIn; // conservative display

        // ITC utilization (per GST law: IGST credit first against IGST, then CGST, then SGST)
        long igstUtilized = Math.min(outIgst, inIgst);
        long remainingIgstCredit = inIgst - igstUtilized;
        long netIgst = outIgst - igstUtilized;

        long cgstUtilized = Math.min(outCgst, inCgst + remainingIgstCredit);
        long netCgst = Math.max(0, outCgst - cgstUtilized);

        long sgstUtilized = Math.min(outSgst, inSgst);
        long netSgst = Math.max(0, outSgst - sgstUtilized);

        long totalNet = netCgst + netSgst + netIgst;

        String currentPeriod = period != null ? period : YearMonth.now().toString();

        return new GstSummary(
            currentPeriod,
            salesTaxable, outCgst, outSgst, outIgst, totalOut,
            purchaseTaxable, inCgst, inSgst, inIgst, totalIn,
            eligibleItc,
            netCgst, netSgst, netIgst, totalNet,
            netCgst, netSgst, netIgst, totalNet,
            outputs.size(), inputs.size()
        );
    }

    public List<InputTaxRow> getInputTax(String period) {
        List<GstInputTaxEntity> list = period != null
            ? inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(period)
            : inputTaxRepo.findAllByOrderByCreatedAtDesc();

        return list.stream().map(e -> new InputTaxRow(
            e.getId(),
            e.getPurchaseOrderUuid() != null ? e.getPurchaseOrderUuid().toString() : null,
            e.getVendorName(),
            e.getVendorGstin(),
            e.getInvoiceNumber(),
            e.getInvoiceDate() != null ? e.getInvoiceDate().toString() : null,
            e.getTaxPeriod(),
            e.getFinancialYear(),
            nvl(e.getTaxableValue()),
            nvl(e.getCgstAmount()),
            nvl(e.getSgstAmount()),
            nvl(e.getIgstAmount()),
            nvl(e.getTotalInputTax()),
            e.getItcEligibility(),
            e.getItcStatus(),
            e.getGstr2bMatchStatus(),
            e.getRemarks(),
            e.getGstType() != null ? e.getGstType() : "INPUT_TAX"
        )).toList();
    }

    public List<OutputTaxRow> getOutputTax(String period) {
        outputTaxSyncer.syncForPeriod(period);
        List<GstOutputTaxEntity> list = period != null
            ? outputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(period)
            : outputTaxRepo.findAllByOrderByCreatedAtDesc();

        return list.stream().map(e -> new OutputTaxRow(
            e.getId(),
            e.getCustomerOrderUuid() != null ? e.getCustomerOrderUuid().toString() : null,
            e.getOrderCode(),
            e.getCustomerName(),
            e.getSupplyType(),
            e.getPlaceOfSupplyStateCode(),
            e.getTaxPeriod(),
            e.getFinancialYear(),
            nvl(e.getTaxableValue()),
            nvl(e.getCgstAmount()),
            nvl(e.getSgstAmount()),
            nvl(e.getIgstAmount()),
            nvl(e.getTotalOutputTax()),
            nvl(e.getInvoiceValue()),
            e.getInvoiceDate() != null ? e.getInvoiceDate().toString() : null,
            e.getGstType() != null ? e.getGstType() : "OUTPUT_TAX"
        )).toList();
    }

    public List<LiabilityRow> getLiability(String period) {
        GstSummary s = getSummary(period);

        long igstUtilized = Math.min(s.outputIgst(), s.inputIgst());
        long remainingIgst = s.inputIgst() - igstUtilized;
        long cgstUtilized = Math.min(s.outputCgst(), s.inputCgst() + remainingIgst);
        long sgstUtilized = Math.min(s.outputSgst(), s.inputSgst());

        return List.of(
            new LiabilityRow("IGST", s.outputIgst(), s.inputIgst(), 0, igstUtilized,
                Math.max(0, s.outputIgst() - igstUtilized)),
            new LiabilityRow("CGST", s.outputCgst(), s.inputCgst(), 0, cgstUtilized,
                Math.max(0, s.outputCgst() - cgstUtilized)),
            new LiabilityRow("SGST", s.outputSgst(), s.inputSgst(), 0, sgstUtilized,
                Math.max(0, s.outputSgst() - sgstUtilized))
        );
    }

    @Transactional
    public void markItcEligibility(Long inputTaxId, String eligibility) {
        inputTaxRepo.findById(inputTaxId).ifPresent(e -> {
            e.setItcEligibility(eligibility);
            if ("ELIGIBLE".equals(eligibility)) {
                e.setEligibleCgst(nvl(e.getCgstAmount()));
                e.setEligibleSgst(nvl(e.getSgstAmount()));
                e.setEligibleIgst(nvl(e.getIgstAmount()));
                e.setIneligibleCgst(0L);
                e.setIneligibleSgst(0L);
                e.setIneligibleIgst(0L);
                e.setItcStatus("ELIGIBLE");
            } else if ("INELIGIBLE".equals(eligibility)) {
                e.setIneligibleCgst(nvl(e.getCgstAmount()));
                e.setIneligibleSgst(nvl(e.getSgstAmount()));
                e.setIneligibleIgst(nvl(e.getIgstAmount()));
                e.setEligibleCgst(0L);
                e.setEligibleSgst(0L);
                e.setEligibleIgst(0L);
                e.setItcStatus("INELIGIBLE");
            }
            inputTaxRepo.save(e);
        });
    }

    private long nvl(Long v) { return v != null ? v : 0L; }
}
