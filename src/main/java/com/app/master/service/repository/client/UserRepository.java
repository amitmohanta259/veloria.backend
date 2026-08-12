package com.app.master.service.repository.client;

import com.app.master.service.core.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmailAndArchiveFalse(String email);

    Optional<UserEntity> findByPhoneAndArchiveFalse(String phone);

    boolean existsByEmailAndArchiveFalse(String email);

    boolean existsByPhoneAndArchiveFalse(String phone);

    @Query("SELECT u FROM UserEntity u WHERE CAST(u.uuid AS string) = :uuidStr AND u.archive = false")
    Optional<UserEntity> findByUuidString(@Param("uuidStr") String uuidStr);
}
