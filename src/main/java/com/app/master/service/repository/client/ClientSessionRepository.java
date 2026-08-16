package com.app.master.service.repository.client;

import com.app.master.service.core.entity.ClientSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface ClientSessionRepository extends JpaRepository<ClientSessionEntity, Long> {

    Optional<ClientSessionEntity> findByToken(String token);

    @Modifying
    @Transactional
    @Query("DELETE FROM ClientSessionEntity s WHERE s.token = :token")
    void deleteByToken(@Param("token") String token);

    @Modifying
    @Transactional
    @Query("DELETE FROM ClientSessionEntity s WHERE s.expiry < :now")
    void deleteExpiredSessions(@Param("now") Instant now);
}
