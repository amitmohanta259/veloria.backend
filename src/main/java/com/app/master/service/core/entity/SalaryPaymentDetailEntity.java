package com.app.master.service.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "salary_payment_detail")
public class SalaryPaymentDetailEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salary_payment_id")
    @JsonIgnore
    private SalaryPaymentEntity salaryPayment;

    private String staffName;
    private String staffCode;
    private String designation;

    private Long basicSalaryPaise;
    private Long grossSalaryPaise;
    private Long netSalaryPaise;

    private Long employerPfPaise;
    private Long employeePfPaise;
    private Long employerEsiPaise;
    private Long employeeEsiPaise;
    private Long totalDeductionsPaise;

    private Integer workingDays;
    private Integer daysWorked;
    private Integer paidLeaveTaken;
    private Integer lopDays;
    private Integer leavesApproved;
    private Integer leavesPending;
    private Integer leavesRejected;
}
