package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.AccountingPeriodEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountingPeriodRepository extends JpaRepository<AccountingPeriodEntity, Long> {
    Optional<AccountingPeriodEntity> findByPeriod(String period);
    List<AccountingPeriodEntity> findAllByOrderByPeriodDesc();

    /**
     * Open periods that begin on or after a date, earliest first.
     *
     * <p>Used to find where a transaction whose own period is closed should be
     * posted instead. Ordered by start date rather than by the period string so
     * the answer stays correct whatever the label looks like.
     */
    List<AccountingPeriodEntity> findByStatusAndPeriodStartGreaterThanEqualOrderByPeriodStartAsc(
            String status, java.time.LocalDate from);
}
