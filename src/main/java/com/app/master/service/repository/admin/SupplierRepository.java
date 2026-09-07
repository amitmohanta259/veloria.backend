package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.SupplierEntity;
import com.app.master.service.core.response.admin.SupplierListResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SupplierRepository extends JpaRepository<SupplierEntity, Long> {

    Optional<SupplierEntity> findByUuid(UUID uuid);

    long countByArchiveFalse();

    long countByStatusAndArchiveFalse(String status);

    @Query("SELECT MAX(CAST(SUBSTRING(s.supplierCode, 5) AS int)) FROM SupplierEntity s WHERE s.supplierCode LIKE 'SUP-%'")
    Optional<Integer> findMaxSupplierCodeNumber();

    @Query("""
            SELECT new com.app.master.service.core.response.admin.SupplierListResponse(
                s.uuid, s.supplierCode, s.name, s.gstn,
                s.category, s.country, s.city, s.status
            )
            FROM SupplierEntity s
            WHERE (:search IS NULL
                   OR LOWER(s.name) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(s.gstn) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(s.supplierCode) LIKE LOWER(CONCAT('%', :search, '%')))
            AND (:category IS NULL OR s.category = :category)
            AND (s.archive = false OR s.status = 'TERMINATED')
            ORDER BY s.created DESC
            """)
    Page<SupplierListResponse> allSuppliers(@Param("search") String search,
                                             @Param("category") String category,
                                             Pageable pageable);

    @Query("""
            SELECT new com.app.master.service.core.response.admin.SupplierListResponse(
                s.uuid, s.supplierCode, s.name, s.gstn,
                s.category, s.country, s.city, s.status
            )
            FROM SupplierEntity s
            WHERE (s.archive = false OR s.status = 'TERMINATED')
            ORDER BY s.created DESC
            """)
    List<SupplierListResponse> listAllSuppliers();

    long countByStatus(String status);

    List<SupplierEntity> findByCategoryInAndArchiveFalse(List<String> categories);
}
