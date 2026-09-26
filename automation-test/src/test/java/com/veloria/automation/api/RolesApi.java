package com.veloria.automation.api;

import java.util.List;
import java.util.Map;

/** /roles — role definitions and module permissions. */
public final class RolesApi {
    private RolesApi() {}

    public static Http.Response list()                 { return Http.get("/roles", null); }
    public static Http.Response get(String uuid)       { return Http.get("/roles/" + uuid, null); }
    public static Http.Response create(Object body)    { return Http.post("/roles", body, null); }
    public static Http.Response update(String uuid, Object body) { return Http.put("/roles/" + uuid, body, null); }
    public static Http.Response delete(String uuid)    { return Http.delete("/roles/" + uuid, null); }

    public static Map<String, Object> permission(String module, boolean view, boolean create, boolean edit, boolean delete) {
        return Map.of("module", module, "canView", view, "canCreate", create, "canEdit", edit, "canDelete", delete);
    }

    public static Map<String, Object> request(String department, String designation, List<Map<String, Object>> perms) {
        return designation == null
                ? Map.of("department", department, "permissions", perms)
                : Map.of("department", department, "designation", designation, "permissions", perms);
    }

    public static Http.Response staffByDepartment(String department) {
        return Http.get("/staff/list?pageSize=5&department=" + department, null);
    }
}
