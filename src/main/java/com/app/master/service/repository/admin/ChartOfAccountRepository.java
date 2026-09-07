package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.ChartOfAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChartOfAccountRepository extends JpaRepository<ChartOfAccountEntity, Long> {
    Optional<ChartOfAccountEntity> findByCode(String code);
    List<ChartOfAccountEntity> findByActiveTrueOrderByCodeAsc();
    List<ChartOfAccountEntity> findByAccountTypeAndActiveTrueOrderByCodeAsc(String accountType);
}
