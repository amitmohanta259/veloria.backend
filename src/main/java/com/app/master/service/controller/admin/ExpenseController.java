package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.ExpenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/expense")
@RequiredArgsConstructor
public class ExpenseController extends AppController {

    private final ExpenseService expenseService;

    @GetMapping("/list")
    public ResponseEntity<Response> listExpenses() {
        return data(ResponseCode.FETCHED, "Expenses fetched successfully", expenseService.getAllExpenses());
    }

    @PostMapping("/create")
    public ResponseEntity<Response> createExpense(@RequestBody ExpenseService.CreateExpenseRequest req) {
        return success(ResponseCode.CREATED, "Expense created successfully", expenseService.createExpense(req));
    }

    @GetMapping("/vendors")
    public ResponseEntity<Response> getVendors(@RequestParam(required = false, defaultValue = "") String expenseType) {
        return data(ResponseCode.FETCHED, "Vendors fetched successfully", expenseService.getVendorsByExpenseType(expenseType));
    }
}
