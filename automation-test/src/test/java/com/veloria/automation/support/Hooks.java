package com.veloria.automation.support;

import io.cucumber.java.After;
import io.cucumber.java.Scenario;

import java.util.Collections;
import java.util.List;

/** Undoes whatever a scenario seeded, even when a step failed. */
public class Hooks {

    private final ScenarioContext ctx;

    public Hooks(ScenarioContext ctx) { this.ctx = ctx; }

    @After(order = 0)
    public void cleanUp(Scenario scenario) {
        List<Runnable> steps = ctx.cleanups;
        Collections.reverse(steps);
        for (Runnable r : steps) {
            try { r.run(); }
            catch (RuntimeException e) { scenario.log("cleanup failed: " + e.getMessage()); }
        }
        steps.clear();
    }
}
