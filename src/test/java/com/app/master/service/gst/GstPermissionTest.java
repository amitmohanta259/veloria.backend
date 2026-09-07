package com.app.master.service.gst;

import com.app.master.service.core.security.GstPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.app.master.service.core.security.GstPermission.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Role/permission model (spec sections 42 and 43).
 *
 * These assertions encode the separation-of-duties intent: the person who
 * enters a purchase invoice is not the person who approves its ITC, and only
 * an admin can unlock a locked period.
 */
class GstPermissionTest {

    @Test
    @DisplayName("A viewer can read but change nothing")
    void viewerIsReadOnly() {
        Set<String> p = permissionsOf(GST_VIEWER);
        assertTrue(p.contains(VIEW_GST));
        assertTrue(p.contains(VIEW_GST_LEDGER));
        assertTrue(p.contains(VIEW_ITC));
        assertFalse(p.contains(CREATE_INVOICE));
        assertFalse(p.contains(APPROVE_ITC));
        assertFalse(p.contains(LOCK_PERIOD));
        assertFalse(p.contains(EXPORT_GST_REPORT));
    }

    @Test
    @DisplayName("An operator creates documents but approves nothing")
    void operatorCannotApprove() {
        Set<String> p = permissionsOf(GST_OPERATOR);
        assertTrue(p.contains(CREATE_PURCHASE));
        assertTrue(p.contains(CREATE_INVOICE));
        assertTrue(p.contains(CREATE_CREDIT_NOTE));
        assertFalse(p.contains(APPROVE_ITC), "Separation of duties: entry is not approval");
        assertFalse(p.contains(REVERSE_ITC));
        assertFalse(p.contains(LOCK_PERIOD));
    }

    @Test
    @DisplayName("An accountant prepares returns but cannot approve ITC or lock a period")
    void accountantPreparesOnly() {
        Set<String> p = permissionsOf(GST_ACCOUNTANT);
        assertTrue(p.contains(PREPARE_GSTR1));
        assertTrue(p.contains(PREPARE_GSTR3B));
        assertTrue(p.contains(RECONCILE_GSTR2B));
        assertTrue(p.contains(EXPORT_GST_REPORT));
        assertFalse(p.contains(APPROVE_ITC));
        assertFalse(p.contains(LOCK_PERIOD));
        assertFalse(p.contains(FILE_RETURN));
    }

    @Test
    @DisplayName("An approver signs off but does not create the documents it approves")
    void approverDoesNotCreate() {
        Set<String> p = permissionsOf(GST_APPROVER);
        assertTrue(p.contains(APPROVE_ITC));
        assertTrue(p.contains(REVERSE_ITC));
        assertTrue(p.contains(RECLAIM_ITC));
        assertTrue(p.contains(LOCK_PERIOD));
        assertFalse(p.contains(CREATE_PURCHASE),
                "Separation of duties: the approver should not also enter the purchase");
        assertFalse(p.contains(CREATE_INVOICE));
        assertFalse(p.contains(UNLOCK_PERIOD), "Only an admin may unlock");
    }

    @Test
    @DisplayName("An admin holds every permission")
    void adminHoldsEverything() {
        Set<String> p = permissionsOf(GST_ADMIN);
        for (String perm : new String[]{
                VIEW_GST, VIEW_GST_LEDGER, VIEW_ITC, CREATE_PURCHASE, CREATE_INVOICE,
                APPROVE_ITC, REVERSE_ITC, RECLAIM_ITC, CREATE_CREDIT_NOTE, CREATE_DEBIT_NOTE,
                PREPARE_GSTR1, PREPARE_GSTR3B, LOCK_PERIOD, UNLOCK_PERIOD,
                RECONCILE_GSTR2B, EXPORT_GST_REPORT, FILE_RETURN, ADMIN_GST}) {
            assertTrue(p.contains(perm), "GST_ADMIN is missing " + perm);
        }
    }

    @Test
    @DisplayName("An unknown role grants nothing")
    void unknownRoleGrantsNothing() {
        assertTrue(permissionsOf("SUPER_USER").isEmpty());
        assertTrue(permissionsOf(null).isEmpty());
        assertTrue(permissionsOf("").isEmpty());
    }

    @Test
    @DisplayName("Role names are matched case-insensitively but not loosely")
    void roleLookupIsCaseInsensitive() {
        assertEquals(permissionsOf(GST_ADMIN), permissionsOf("gst_admin"));
        assertTrue(permissionsOf("gst admin").isEmpty(), "A space is not a valid role name");
    }

    @Test
    @DisplayName("Every declared role resolves to a non-empty permission set")
    void allRolesResolve() {
        for (String role : GstPermission.ALL_ROLES) {
            assertFalse(permissionsOf(role).isEmpty(), role + " resolves to nothing");
        }
    }
}
