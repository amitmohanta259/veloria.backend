package com.app.master.service.repository.payment;

import com.app.master.service.core.entity.PaymentWebhookEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEventEntity, Long> {

    Optional<PaymentWebhookEventEntity> findByEventId(String eventId);

    boolean existsByEventId(String eventId);
}
