package com.app.master.service.service.admin;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.admin.GstOutputTaxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Separate bean so @Transactional is properly applied via Spring proxy.
 * Backfills gst_output_tax for orders that predate the eager-write path.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstOutputTaxSyncer {

    private final GstOutputTaxRepository outputTaxRepo;
    private final CustomerOrderRepository orderRepo;

    @Transactional
    public void syncForPeriod(String period) {
        List<CustomerOrderEntity> orders = orderRepo.findAll().stream()
            .filter(o -> !Boolean.TRUE.equals(o.getArchive()) && o.getOrderPlacedAt() != null)
            .filter(o -> {
                if (period == null) return nvl(o.getCgstAmount()) > 0 || nvl(o.getIgstAmount()) > 0;
                String p = YearMonth.from(o.getOrderPlacedAt().atZone(ZoneId.of("Asia/Kolkata"))).toString();
                return period.equals(p);
            })
            .toList();

        for (CustomerOrderEntity order : orders) {
            if (nvl(order.getCgstAmount()) == 0 && nvl(order.getIgstAmount()) == 0) continue;
            try {
                YearMonth ym = YearMonth.from(order.getOrderPlacedAt().atZone(ZoneId.of("Asia/Kolkata")));
                int yr = ym.getYear();
                String fy = (ym.getMonthValue() >= 4)
                    ? yr + "-" + String.format("%02d", (yr + 1) % 100)
                    : (yr - 1) + "-" + String.format("%02d", yr % 100);
                String supplyType = Objects.equals(order.getBuyerStateCode(), order.getSellerStateCode())
                    ? "INTRA_STATE" : "INTER_STATE";

                outputTaxRepo.insertIfAbsent(
                    order.getId(), order.getUuid(), order.getOrderCode(), order.getCustomerName(),
                    null, order.getPlaceOfSupply(), supplyType, fy, ym.toString(),
                    order.getOrderPlacedAt().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(),
                    nvl(order.getTaxableValue()), BigDecimal.ZERO, nvl(order.getCgstAmount()),
                    BigDecimal.ZERO, nvl(order.getSgstAmount()), BigDecimal.ZERO, nvl(order.getIgstAmount()),
                    nvl(order.getTotalTaxAmount()), nvl(order.getTotalValue()), "NONE"
                );
            } catch (Exception e) {
                log.warn("Output-tax sync skipped for order {}: {}", order.getOrderCode(), e.getMessage());
            }
        }
    }

    private long nvl(Long v) { return v != null ? v : 0L; }
}
