package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/analytics")
public class AnalyticsController extends AppController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/summary")
    public ResponseEntity<Response> getSummary() {
        return success(ResponseCode.FETCHED, "Analytics summary fetched", analyticsService.getSummary());
    }
}
