package com.app.master.service.repository.client;

import com.app.master.service.core.entity.UserAddressEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserAddressRepository extends JpaRepository<UserAddressEntity, Long> {

    List<UserAddressEntity> findByUserIdAndArchiveFalseOrderByIsDefaultDescCreatedAtAsc(String userId);
}
