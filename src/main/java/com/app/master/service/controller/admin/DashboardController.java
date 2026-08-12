package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/dashboard")
public class DashboardController extends AppController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public ResponseEntity<Response> getSummary() {
        return success(ResponseCode.FETCHED, "Dashboard summary fetched", dashboardService.getSummary());
    }

    @GetMapping("/orders")
    public ResponseEntity<Response> getOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return success(ResponseCode.FETCHED, "Orders fetched", dashboardService.getOrders(status, page, size));
    }
}
