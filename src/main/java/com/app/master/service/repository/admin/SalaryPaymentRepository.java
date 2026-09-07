package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.SalaryPaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SalaryPaymentRepository extends JpaRepository<SalaryPaymentEntity, Long> {

    List<SalaryPaymentEntity> findAllByOrderByPaymentMonthDesc();

    Optional<SalaryPaymentEntity> findByUuid(UUID uuid);

    @Query("SELECT MAX(CAST(SUBSTRING(s.voucherNumber, 10) AS int)) FROM SalaryPaymentEntity s WHERE s.voucherNumber LIKE 'SAL-%'")
    Optional<Integer> findMaxVoucherNumber();
}
