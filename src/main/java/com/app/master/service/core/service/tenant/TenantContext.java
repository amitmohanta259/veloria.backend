package com.app.master.service.core.service.tenant;

import com.app.master.service.core.constant.Constant;
import org.apache.commons.lang3.ObjectUtils;

public class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    public static String getCurrentTenant() {
        return ObjectUtils.isNotEmpty(CURRENT_TENANT.get()) ? CURRENT_TENANT.get() : Constant.PUBLIC;
    }

    public static void setCurrentTenant(String tenant) {
        CURRENT_TENANT.set(tenant);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }

}