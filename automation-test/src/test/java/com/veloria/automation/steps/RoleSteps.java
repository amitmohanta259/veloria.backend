package com.veloria.automation.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.veloria.automation.api.Http;
import com.veloria.automation.api.RolesApi;
import com.veloria.automation.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RoleSteps {

    private final ScenarioContext ctx;

    public RoleSteps(ScenarioContext ctx) { this.ctx = ctx; }

    @When("the roles are listed")
    public void rolesListed() { ctx.lastResponse = RolesApi.list(); }

    @When("a role is created for department {string} with designation {string} granting view on {string}")
    public void roleCreated(String department, String designation, String module) {
        ctx.lastResponse = RolesApi.create(RolesApi.request(department, designation,
                List.of(RolesApi.permission(module, true, false, false, false))));
        captureRole();
    }

    @Given("a role exists for department {string} with designation {string} granting view on {string}")
    public void roleExists(String department, String designation, String module) {
        roleCreated(department, designation, module);
        assertEquals(201, ctx.lastResponse.status(), "could not create the setup role: " + ctx.lastResponse.raw());
    }

    @When("the role is updated to grant view on {string} only")
    public void roleUpdated(String module) {
        JsonNode role = ctx.lastResponse.data();
        String department = role.get("department").asText();
        String designation = role.hasNonNull("designation") ? role.get("designation").asText() : null;
        ctx.lastResponse = RolesApi.update(ctx.var("roleUuid"), RolesApi.request(department, designation,
                List.of(RolesApi.permission(module, true, false, false, false))));
    }

    @When("that role is read back")
    public void roleReadBack() { ctx.lastResponse = RolesApi.get(ctx.var("roleUuid")); }

    @When("the role is deleted")
    public void roleDeleted() { ctx.lastResponse = RolesApi.delete(ctx.var("roleUuid")); }

    @When("staff are listed for department {string}")
    public void staffListed(String department) { ctx.lastResponse = RolesApi.staffByDepartment(department); }

    @Then("every role reports {int} modules")
    public void everyRoleReportsNModules(int n) {
        for (JsonNode role : ctx.lastResponse.data()) {
            assertEquals(n, role.get("permissions").size(), "role " + role.get("scopeLabel"));
        }
    }

    @Then("the modules include {string}, {string}, {string} and {string}")
    public void modulesInclude(String a, String b, String c, String d) {
        JsonNode first = ctx.lastResponse.data().get(0);
        String modules = first.get("permissions").toString();
        for (String m : List.of(a, b, c, d)) assertTrue(modules.contains("\"" + m + "\""), "missing module " + m);
    }

    @Then("the role reports {string} with view granted")
    public void roleReportsViewGranted(String module) { assertTrue(view(module), "view on " + module + " not granted"); }

    @Then("the role reports {string} without view")
    public void roleReportsWithoutView(String module) { assertFalse(view(module), "view on " + module + " should be off"); }

    private boolean view(String module) {
        for (JsonNode p : ctx.lastResponse.data().get("permissions")) {
            if (module.equals(p.get("module").asText())) return p.get("canView").asBoolean();
        }
        fail("module " + module + " not in response: " + ctx.lastResponse.raw());
        return false;
    }

    private void captureRole() {
        Http.Response r = ctx.lastResponse;
        if (r.ok() && r.data() != null && r.data().hasNonNull("uuid")) {
            String uuid = r.data().get("uuid").asText();
            ctx.vars.put("roleUuid", uuid);
            ctx.onCleanup(() -> RolesApi.delete(uuid));
        }
    }
}
