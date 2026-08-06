package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.ReturnsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/returns")
public class ReturnsController extends AppController {

    private final ReturnsService service;

    public ReturnsController(ReturnsService service) {
        this.service = service;
    }

    @GetMapping("/all")
    public ResponseEntity<Response> all(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int pageSize) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Returns fetched successfully",
                service.getReturnsLog(month, year, search, page, pageSize));
    }

    @GetMapping("/stats")
    public ResponseEntity<Response> stats() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Returns stats fetched successfully", service.getStats());
    }

    @GetMapping("/trends")
    public ResponseEntity<Response> trends(
            @RequestParam(defaultValue = "weekly") String period) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Trends fetched successfully", service.getTrends(period));
    }

    @GetMapping("/reasons")
    public ResponseEntity<Response> reasons() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Return reasons fetched successfully", service.getReasons());
    }
}
