package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.FinancialIndicatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Financial ratios for the indicators screen.
 *
 * Read-only, and derived entirely from the general ledger — a ratio here cannot
 * disagree with the statement it came from.
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/indicators")
@RequiredArgsConstructor
public class IndicatorsController extends AppController {

    private final FinancialIndicatorService indicatorService;

    @GetMapping
    public ResponseEntity<Response> indicators(@RequestParam(required = false) String period) {
        String p = period == null || period.isBlank()
                ? YearMonth.now(ZoneId.of("Asia/Kolkata")).toString()
                : period;
        return data(ResponseCode.FETCHED, "Financial indicators",
                indicatorService.indicators(p));
    }
}
