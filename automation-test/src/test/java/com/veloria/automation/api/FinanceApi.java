package com.veloria.automation.api;

public final class FinanceApi {
    private FinanceApi() {}

    public static Http.Response dashboardSummary()          { return Http.get("/dashboard/summary", null); }
    public static Http.Response trialBalance(String period)  { return Http.get("/accounting/trial-balance" + (period == null ? "" : "?period=" + period), null); }
    public static Http.Response reconciliation(String period){ return Http.get("/accounting/reconciliation" + (period == null ? "" : "?period=" + period), null); }
    public static Http.Response indicators(String period)    { return Http.get("/indicators" + (period == null ? "" : "?period=" + period), null); }
}
