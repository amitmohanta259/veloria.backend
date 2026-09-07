package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.AccountingPeriodEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriodEntity, Long> {
    Optional<AccountingPeriodEntity> findByPeriod(String period);
    List<AccountingPeriodEntity> findAllByOrderByPeriodDesc();
}
