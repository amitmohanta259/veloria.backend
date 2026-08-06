package com.app.master.service.service.admin;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.ReturnReasonResponse;
import com.app.master.service.core.response.admin.ReturnTrendPointResponse;
import com.app.master.service.core.response.admin.ReturnsLogResponse;
import com.app.master.service.core.response.admin.ReturnsStatsResponse;
import org.springframework.data.domain.Page;

import java.util.List;

public interface ReturnsService {

    Page<ReturnsLogResponse> getReturnsLog(Integer month, Integer year, String search, int page, int pageSize) throws VeloriaException;

    ReturnsStatsResponse getStats() throws VeloriaException;

    List<ReturnTrendPointResponse> getTrends(String period) throws VeloriaException;

    List<ReturnReasonResponse> getReasons() throws VeloriaException;
}
