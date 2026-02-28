package com.app.master.service.core.config;

import com.app.master.service.core.service.AppService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;

@Slf4j
public class TenantContextHolder extends AppService {

    private static final ThreadLocal<String> CONTEXT = new InheritableThreadLocal<>();

    public void setTenantId(String tenant) {
        CONTEXT.set(tenant);
    }

    private HttpServletRequest getCurrentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        return attributes.getRequest();
    }

    public String getTenant() {
//        String schema = CONTEXT.get();
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            String schema = request.getHeader("X-TENANT-ID");
            if (schema != null && !schema.isEmpty()) {
                return schema.toLowerCase();
            } else {
                try {
                    return Objects.requireNonNull(getCurrentUser()).getTenantGroup();
                } catch (Exception e) {
                    log.error("Failed to find schema name from current user");
                    return null;
                }
            }
        } else {
            try {
                return Objects.requireNonNull(getCurrentUser()).getTenantGroup();
            } catch (Exception e) {
                log.error("Failed to find schema name from current user");
                return null;
            }
        }
    }

    public void clear() {
        CONTEXT.remove();
    }

}