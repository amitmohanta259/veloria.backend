package com.app.master.service.core.service.impl;

import com.app.master.service.core.dto.User;
import com.app.master.service.core.service.AppService;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.data.domain.AuditorAware;

import java.util.Optional;

public class AuditorAwareService extends AppService implements AuditorAware<String> {

    @SneakyThrows
    @Override
    @NonNull
    public Optional<String> getCurrentAuditor() {
        try {
            User currentUser = getCurrentUser();
            Optional<String> data = ObjectUtils.isNotEmpty(currentUser.getIamId())
                    ? Optional.of(currentUser.getIamId())
                    : Optional.of("SELF");
            return data;
        } catch (Exception e) {
            return Optional.of("SELF");
        }
    }
}
