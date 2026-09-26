package com.veloria.automation.support;

import com.veloria.automation.api.Http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * State for one scenario, injected into every step class by PicoContainer.
 *
 * Steps talk to each other only through this object, which is what lets
 * "the buyer has a session" live in one class and be reused by cart, order
 * and return steps without duplicating the definition.
 */
public class ScenarioContext {

    /** Last HTTP exchange, so "the response status should be" works for any call. */
    public Http.Response lastResponse;

    /** Bearer token of the buyer the scenario is acting as. */
    public String clientToken;
    /** A second buyer, for isolation checks. */
    public String otherClientToken;
    /** GST admin token from the dev issuer. */
    public String gstToken;

    /** Named values captured during the scenario: productUuid, orderCode, roleUuid… */
    public final Map<String, Object> vars = new HashMap<>();

    /** Rows seeded by this scenario, undone by Hooks in reverse order. */
    public final List<Runnable> cleanups = new ArrayList<>();

    public String var(String name) {
        Object v = vars.get(name);
        if (v == null) throw new IllegalStateException("Nothing captured as '" + name + "' yet");
        return String.valueOf(v);
    }

    public void onCleanup(Runnable r) { cleanups.add(r); }
}
