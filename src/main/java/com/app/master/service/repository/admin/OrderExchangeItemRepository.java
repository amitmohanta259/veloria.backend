package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.OrderExchangeItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderExchangeItemRepository extends JpaRepository<OrderExchangeItemEntity, Long> {

    List<OrderExchangeItemEntity> findByExchangeRequestIdOrderByIdAsc(Long exchangeRequestId);

    List<OrderExchangeItemEntity> findByExchangeRequestIdAndSideOrderByIdAsc(Long exchangeRequestId, String side);
}
