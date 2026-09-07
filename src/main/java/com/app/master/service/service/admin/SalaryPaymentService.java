package com.app.master.service.service.admin;

import com.app.master.service.core.entity.SalaryPaymentDetailEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.entity.SalaryPaymentEntity;
import com.app.master.service.repository.admin.SalaryPaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SalaryPaymentService {

    public static final String PENDING = "PENDING";
    public static final String PAID    = "PAID";

    private final SalaryPaymentRepository salaryPaymentRepo;

    // ---- DTOs ----

    public record SalaryPaymentRow(
        Long id, String uuid, String voucherNumber, String paymentMonth,
        long totalGrossPaise, long totalNetPaise,
        long totalEmployerContributionPaise, long totalEmployeeContributionPaise,
        int employeeCount, String status, String notes
    ) {}

    public record SalaryDetailDto(
        Long id, String staffName, String staffCode, String designation,
        long basicSalaryPaise, long grossSalaryPaise, long netSalaryPaise,
        long employerPfPaise, long employeePfPaise,
        long employerEsiPaise, long employeeEsiPaise, long totalDeductionsPaise,
        int workingDays, int daysWorked, int paidLeaveTaken, int lopDays,
        int leavesApproved, int leavesPending, int leavesRejected
    ) {}

    public record SalaryPaymentDetailView(
        Long id, String uuid, String voucherNumber, String paymentMonth,
        long totalGrossPaise, long totalNetPaise,
        long totalEmployerContributionPaise, long totalEmployeeContributionPaise,
        int employeeCount, String status, String notes,
        List<SalaryDetailDto> details
    ) {}

    public record EmployeeDetailRequest(
        String staffName, String staffCode, String designation,
        long basicSalaryPaise, long grossSalaryPaise, long netSalaryPaise,
        long employerPfPaise, long employeePfPaise,
        long employerEsiPaise, long employeeEsiPaise,
        int workingDays, int daysWorked, int paidLeaveTaken, int lopDays,
        int leavesApproved, int leavesPending, int leavesRejected
    ) {}

    public record CreateSalaryPaymentRequest(
        String paymentMonth, String status, String notes,
        List<EmployeeDetailRequest> employees
    ) {}

    // ---- Public methods ----

    public List<SalaryPaymentRow> getAllPayments() {
        return salaryPaymentRepo.findAllByOrderByPaymentMonthDesc().stream().map(this::toRow).toList();
    }

    public SalaryPaymentDetailView getPaymentById(Long id) {
        return salaryPaymentRepo.findById(id).map(this::toDetailView).orElse(null);
    }

    @Transactional
    public SalaryPaymentRow createPayment(CreateSalaryPaymentRequest req) {
        String voucher = generateVoucher();

        long totalGross = 0L, totalNet = 0L, totalErPf = 0L, totalEePf = 0L;
        if (req.employees() != null) {
            for (EmployeeDetailRequest e : req.employees()) {
                totalGross += e.grossSalaryPaise();
                totalNet += e.netSalaryPaise();
                totalErPf += e.employerPfPaise();
                totalEePf += e.employeePfPaise();
            }
        }

        YearMonth ym = req.paymentMonth() != null
            ? YearMonth.parse(req.paymentMonth())
            : YearMonth.now();

        String notesText = req.notes() != null ? req.notes()
            : monthLabel(ym) + " Salary — Payment made against employee salaries for "
              + (req.employees() != null ? req.employees().size() : 0) + " staff members";

        SalaryPaymentEntity payment = SalaryPaymentEntity.builder()
            .voucherNumber(voucher)
            .paymentMonth(ym.toString())
            .totalGrossPaise(totalGross)
            .totalNetPaise(totalNet)
            .totalEmployerContributionPaise(totalErPf)
            .totalEmployeeContributionPaise(totalEePf)
            .employeeCount(req.employees() != null ? req.employees().size() : 0)
            .status(req.status() != null ? req.status() : PENDING)
            .notes(notesText)
            .build();

        if (req.employees() != null) {
            for (EmployeeDetailRequest e : req.employees()) {
                SalaryPaymentDetailEntity detail = SalaryPaymentDetailEntity.builder()
                    .salaryPayment(payment)
                    .staffName(e.staffName())
                    .staffCode(e.staffCode())
                    .designation(e.designation())
                    .basicSalaryPaise(e.basicSalaryPaise())
                    .grossSalaryPaise(e.grossSalaryPaise())
                    .netSalaryPaise(e.netSalaryPaise())
                    .employerPfPaise(e.employerPfPaise())
                    .employeePfPaise(e.employeePfPaise())
                    .employerEsiPaise(e.employerEsiPaise())
                    .employeeEsiPaise(e.employeeEsiPaise())
                    .totalDeductionsPaise(e.employeePfPaise() + e.employeeEsiPaise())
                    .workingDays(e.workingDays())
                    .daysWorked(e.daysWorked())
                    .paidLeaveTaken(e.paidLeaveTaken())
                    .lopDays(e.lopDays())
                    .leavesApproved(e.leavesApproved())
                    .leavesPending(e.leavesPending())
                    .leavesRejected(e.leavesRejected())
                    .build();
                payment.getDetails().add(detail);
            }
        }

        return toRow(salaryPaymentRepo.save(payment));
    }

    /**
     * Marks a salary payment as paid.
     *
     * Refused when it is already paid rather than silently succeeding: a second
     * click should tell the user the disbursement was already recorded, not
     * imply a fresh one happened. {@code updatedAt} is maintained by the entity,
     * so when it was paid is recorded.
     */
    @Transactional
    public SalaryPaymentRow markAsPaid(Long id) throws VeloriaException {
        SalaryPaymentEntity payment = salaryPaymentRepo.findById(id)
            .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                "Salary payment " + id + " was not found"));

        if (PAID.equalsIgnoreCase(payment.getStatus())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                "Salary payment " + payment.getVoucherNumber() + " is already marked paid");
        }

        payment.setStatus(PAID);
        return toRow(salaryPaymentRepo.save(payment));
    }

    // ---- Helpers ----

    private String generateVoucher() {
        int year = LocalDate.now().getYear();
        int next = salaryPaymentRepo.findMaxVoucherNumber().map(n -> n + 1).orElse(1);
        return String.format("SAL-%d-%04d", year, next);
    }

    private String monthLabel(YearMonth ym) {
        return ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"));
    }

    private SalaryPaymentRow toRow(SalaryPaymentEntity e) {
        return new SalaryPaymentRow(
            e.getId(), e.getUuid() != null ? e.getUuid().toString() : null,
            e.getVoucherNumber(), e.getPaymentMonth(),
            nvl(e.getTotalGrossPaise()), nvl(e.getTotalNetPaise()),
            nvl(e.getTotalEmployerContributionPaise()), nvl(e.getTotalEmployeeContributionPaise()),
            e.getEmployeeCount() != null ? e.getEmployeeCount() : 0,
            e.getStatus(), e.getNotes()
        );
    }

    private SalaryPaymentDetailView toDetailView(SalaryPaymentEntity e) {
        List<SalaryDetailDto> details = e.getDetails().stream().map(d -> new SalaryDetailDto(
            d.getId(), d.getStaffName(), d.getStaffCode(), d.getDesignation(),
            nvl(d.getBasicSalaryPaise()), nvl(d.getGrossSalaryPaise()), nvl(d.getNetSalaryPaise()),
            nvl(d.getEmployerPfPaise()), nvl(d.getEmployeePfPaise()),
            nvl(d.getEmployerEsiPaise()), nvl(d.getEmployeeEsiPaise()), nvl(d.getTotalDeductionsPaise()),
            nvi(d.getWorkingDays()), nvi(d.getDaysWorked()),
            nvi(d.getPaidLeaveTaken()), nvi(d.getLopDays()),
            nvi(d.getLeavesApproved()), nvi(d.getLeavesPending()), nvi(d.getLeavesRejected())
        )).toList();

        return new SalaryPaymentDetailView(
            e.getId(), e.getUuid() != null ? e.getUuid().toString() : null,
            e.getVoucherNumber(), e.getPaymentMonth(),
            nvl(e.getTotalGrossPaise()), nvl(e.getTotalNetPaise()),
            nvl(e.getTotalEmployerContributionPaise()), nvl(e.getTotalEmployeeContributionPaise()),
            e.getEmployeeCount() != null ? e.getEmployeeCount() : 0,
            e.getStatus(), e.getNotes(), details
        );
    }

    private long nvl(Long v) { return v != null ? v : 0L; }
    private int nvi(Integer v) { return v != null ? v : 0; }
}
