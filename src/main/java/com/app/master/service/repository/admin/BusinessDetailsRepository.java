package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.BusinessDetailsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessDetailsRepository extends JpaRepository<BusinessDetailsEntity, Long> {

    Optional<BusinessDetailsEntity> findFirstByArchiveFalseOrderByIdAsc();
}
