package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.OrderExchangeRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderExchangeRequestRepository extends JpaRepository<OrderExchangeRequestEntity, Long> {

    Optional<OrderExchangeRequestEntity> findByExchangeNumber(String exchangeNumber);

    List<OrderExchangeRequestEntity> findByOriginalOrderIdOrderByIdAsc(Long originalOrderId);

    List<OrderExchangeRequestEntity> findByOrganizationIdOrderByIdDesc(Long organizationId);

    List<OrderExchangeRequestEntity> findByOrganizationIdAndTaxPeriodOrderByIdAsc(Long organizationId, String taxPeriod);
}
