package com.app.master.service.core.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GST permissions and the roles that carry them (spec sections 42 and 43).
 *
 * Authority strings are what {@code @PreAuthorize} checks. A role grants a
 * fixed set of permissions; the token carries roles, and the authentication
 * converter expands them into permission authorities so endpoints can be
 * annotated with the permission they actually need rather than a role name.
 */
public final class GstPermission {

    private GstPermission() {}

    // ── Permissions ──────────────────────────────────────────────────────────

    public static final String VIEW_GST            = "VIEW_GST";
    public static final String VIEW_GST_LEDGER     = "VIEW_GST_LEDGER";
    public static final String VIEW_ITC            = "VIEW_ITC";
    public static final String CREATE_PURCHASE     = "CREATE_PURCHASE";
    public static final String CREATE_INVOICE      = "CREATE_INVOICE";
    public static final String APPROVE_ITC         = "APPROVE_ITC";
    public static final String REVERSE_ITC         = "REVERSE_ITC";
    public static final String RECLAIM_ITC         = "RECLAIM_ITC";
    public static final String CREATE_CREDIT_NOTE  = "CREATE_CREDIT_NOTE";
    public static final String CREATE_DEBIT_NOTE   = "CREATE_DEBIT_NOTE";
    public static final String PREPARE_GSTR1       = "PREPARE_GSTR1";
    public static final String PREPARE_GSTR3B      = "PREPARE_GSTR3B";
    public static final String LOCK_PERIOD         = "LOCK_PERIOD";
    public static final String UNLOCK_PERIOD       = "UNLOCK_PERIOD";
    public static final String RECONCILE_GSTR2B    = "RECONCILE_GSTR2B";
    public static final String EXPORT_GST_REPORT   = "EXPORT_GST_REPORT";
    public static final String FILE_RETURN         = "FILE_RETURN";
    public static final String ADMIN_GST           = "ADMIN_GST";

    // ── Roles ────────────────────────────────────────────────────────────────

    public static final String GST_VIEWER     = "GST_VIEWER";
    public static final String GST_OPERATOR   = "GST_OPERATOR";
    public static final String GST_ACCOUNTANT = "GST_ACCOUNTANT";
    public static final String GST_APPROVER   = "GST_APPROVER";
    public static final String GST_ADMIN      = "GST_ADMIN";

    private static final Set<String> VIEWER_PERMS = Set.of(
            VIEW_GST, VIEW_GST_LEDGER, VIEW_ITC);

    private static final Set<String> OPERATOR_PERMS = union(VIEWER_PERMS, Set.of(
            CREATE_PURCHASE, CREATE_INVOICE, CREATE_CREDIT_NOTE));

    private static final Set<String> ACCOUNTANT_PERMS = union(OPERATOR_PERMS, Set.of(
            CREATE_DEBIT_NOTE, RECONCILE_GSTR2B, PREPARE_GSTR1, PREPARE_GSTR3B,
            EXPORT_GST_REPORT));

    /**
     * An approver signs off on tax positions. Deliberately does not inherit
     * document creation: the person approving ITC should not be the person who
     * entered the purchase invoice.
     */
    private static final Set<String> APPROVER_PERMS = union(VIEWER_PERMS, Set.of(
            APPROVE_ITC, REVERSE_ITC, RECLAIM_ITC, LOCK_PERIOD, EXPORT_GST_REPORT));

    private static final Set<String> ADMIN_PERMS = union(
            union(ACCOUNTANT_PERMS, APPROVER_PERMS),
            Set.of(UNLOCK_PERIOD, FILE_RETURN, ADMIN_GST));

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            GST_VIEWER, VIEWER_PERMS,
            GST_OPERATOR, OPERATOR_PERMS,
            GST_ACCOUNTANT, ACCOUNTANT_PERMS,
            GST_APPROVER, APPROVER_PERMS,
            GST_ADMIN, ADMIN_PERMS);

    public static final List<String> ALL_ROLES = List.of(
            GST_VIEWER, GST_OPERATOR, GST_ACCOUNTANT, GST_APPROVER, GST_ADMIN);

    /** Permissions carried by a role, or empty for an unknown role. */
    public static Set<String> permissionsOf(String role) {
        if (role == null) return Set.of();
        return ROLE_PERMISSIONS.getOrDefault(role.trim().toUpperCase(), Set.of());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
