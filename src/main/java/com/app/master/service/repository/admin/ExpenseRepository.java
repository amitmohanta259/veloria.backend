package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.ExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExpenseRepository extends JpaRepository<ExpenseEntity, Long> {

    List<ExpenseEntity> findAllByOrderByCreatedAtDesc();

    Optional<ExpenseEntity> findByUuid(UUID uuid);

    @Query("SELECT MAX(CAST(SUBSTRING(e.voucherNumber, 10) AS int)) FROM ExpenseEntity e WHERE e.voucherNumber LIKE 'EXP-%'")
    Optional<Integer> findMaxVoucherNumber();
}
