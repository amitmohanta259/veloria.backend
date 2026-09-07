package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.SalaryPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/salary-payment")
@RequiredArgsConstructor
public class SalaryPaymentController extends AppController {

    private final SalaryPaymentService salaryPaymentService;

    @GetMapping("/list")
    public ResponseEntity<Response> list() {
        return data(ResponseCode.FETCHED, "Salary payments fetched successfully", salaryPaymentService.getAllPayments());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Response> getById(@PathVariable Long id) {
        var detail = salaryPaymentService.getPaymentById(id);
        if (detail == null) return data(ResponseCode.FETCHED, "Not found", null);
        return data(ResponseCode.FETCHED, "Salary payment fetched successfully", detail);
    }

    @PostMapping("/create")
    public ResponseEntity<Response> create(@RequestBody SalaryPaymentService.CreateSalaryPaymentRequest req) {
        return success(ResponseCode.CREATED, "Salary payment created successfully", salaryPaymentService.createPayment(req));
    }

    /** Records that this month's salaries have been disbursed. */
    @PostMapping("/{id}/mark-paid")
    public ResponseEntity<Response> markPaid(@PathVariable Long id)
            throws com.app.master.service.core.exception.VeloriaException {
        return success(ResponseCode.UPDATED, "Salary payment marked as paid",
                salaryPaymentService.markAsPaid(id));
    }
}
