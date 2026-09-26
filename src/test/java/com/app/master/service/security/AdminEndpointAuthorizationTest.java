package com.app.master.service.security;

import com.app.master.service.controller.admin.AccountingController;
import com.app.master.service.controller.admin.ExpenseController;
import com.app.master.service.controller.admin.InventoryProductController;
import com.app.master.service.controller.admin.SalaryPaymentController;
import com.app.master.service.core.security.GstPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every state-altering administrative endpoint must carry an authorization rule.
 *
 * Asserted by reflection rather than by listing method names, so an endpoint
 * added later is covered automatically: a new POST/PUT/PATCH/DELETE on these
 * controllers fails this test until someone decides who may call it.
 */
class AdminEndpointAuthorizationTest {

    private static final List<Class<?>> GUARDED = List.of(
            AccountingController.class,
            InventoryProductController.class,
            ExpenseController.class,
            SalaryPaymentController.class);

    /** The authorities this application already defines. No new role is invented. */
    private static final Set<String> KNOWN_AUTHORITIES = Set.of(
            GstPermission.ADMIN_GST, GstPermission.LOCK_PERIOD, GstPermission.UNLOCK_PERIOD,
            GstPermission.VIEW_GST, GstPermission.VIEW_GST_LEDGER, GstPermission.VIEW_ITC,
            GstPermission.CREATE_PURCHASE, GstPermission.CREATE_INVOICE, GstPermission.APPROVE_ITC,
            GstPermission.REVERSE_ITC, GstPermission.RECLAIM_ITC, GstPermission.CREATE_CREDIT_NOTE,
            GstPermission.CREATE_DEBIT_NOTE, GstPermission.PREPARE_GSTR1, GstPermission.PREPARE_GSTR3B,
            GstPermission.RECONCILE_GSTR2B, GstPermission.EXPORT_GST_REPORT, GstPermission.FILE_RETURN);

    private static boolean mutates(Method m) {
        return m.isAnnotationPresent(PostMapping.class)
                || m.isAnnotationPresent(PutMapping.class)
                || m.isAnnotationPresent(PatchMapping.class)
                || m.isAnnotationPresent(DeleteMapping.class);
    }

    @Test
    @DisplayName("Every state-altering admin endpoint carries @PreAuthorize")
    void everyMutatingEndpointIsGuarded() {
        List<String> unguarded = new ArrayList<>();
        for (Class<?> c : GUARDED) {
            for (Method m : c.getDeclaredMethods()) {
                if (mutates(m) && m.getAnnotation(PreAuthorize.class) == null) {
                    unguarded.add(c.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertTrue(unguarded.isEmpty(),
                "these can alter state with no authorization rule: " + unguarded);
    }

    @Test
    @DisplayName("Those rules reference authorities the application already defines")
    void guardsUseExistingAuthorities() {
        List<String> bad = new ArrayList<>();
        for (Class<?> c : GUARDED) {
            for (Method m : c.getDeclaredMethods()) {
                PreAuthorize p = m.getAnnotation(PreAuthorize.class);
                if (p == null) continue;
                boolean known = KNOWN_AUTHORITIES.stream().anyMatch(a -> p.value().contains(a));
                if (!known) bad.add(c.getSimpleName() + "." + m.getName() + " → " + p.value());
            }
        }
        assertTrue(bad.isEmpty(), "authority not part of the existing model: " + bad);
    }

    @Test
    @DisplayName("Closing and reopening a period use the authorities that exist for exactly that")
    void periodControlsUseTheirOwnAuthorities() throws NoSuchMethodException {
        PreAuthorize close = AccountingController.class
                .getMethod("close", String.class, java.util.Map.class).getAnnotation(PreAuthorize.class);
        PreAuthorize reopen = AccountingController.class
                .getMethod("reopen", String.class, java.util.Map.class).getAnnotation(PreAuthorize.class);

        assertNotNull(close);
        assertNotNull(reopen);
        assertTrue(close.value().contains(GstPermission.LOCK_PERIOD),
                "closing a period should need LOCK_PERIOD, not a generic admin authority");
        assertTrue(reopen.value().contains(GstPermission.UNLOCK_PERIOD),
                "reopening a period should need UNLOCK_PERIOD");
    }

    @Test
    @DisplayName("The irreversible accounting operations require the administrative authority")
    void financialMutationsRequireAdmin() {
        List<String> names = List.of("backfill", "reverse", "postOpeningBalances");
        List<String> covered = new ArrayList<>();
        for (Method m : AccountingController.class.getDeclaredMethods()) {
            if (!names.contains(m.getName())) continue;
            covered.add(m.getName());
            PreAuthorize p = m.getAnnotation(PreAuthorize.class);
            assertNotNull(p, m.getName() + " must be guarded");
            assertTrue(p.value().contains(GstPermission.ADMIN_GST),
                    m.getName() + " must require ADMIN_GST, was: " + p.value());
        }
        // Guard against this test silently passing because a method was renamed.
        assertEquals(names.size(), covered.size(),
                "expected to check " + names + " but found " + covered);
    }

    @Test
    @DisplayName("Adding stock is guarded")
    void addStockIsGuarded() throws NoSuchMethodException {
        Method m = InventoryProductController.class.getMethod(
                "addStock", java.util.UUID.class, String.class, long.class);
        PreAuthorize p = m.getAnnotation(PreAuthorize.class);
        assertNotNull(p, "addStock must not be callable without authorization");
        assertTrue(p.value().contains(GstPermission.ADMIN_GST));
    }
}
