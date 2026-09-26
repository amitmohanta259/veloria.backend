# Execution report — 2026-09-20

Environment: local. Backend `:8081` (profile `local`), client `:5174`, admin `:5173`,
PostgreSQL `localhost:5432/postgres`. Every run below was executed; nothing here
is projected.

## Summary

```
Cucumber scenarios (@regression, incl. @mutates): 66
  Passed:  56
  Failed:  10   — all ten are application defects (below); zero automation defects remain
  Blocked:  0

Cucumber @smoke: 13 — 11 passed, 2 failed (both AUTH/CART "missing header → 400", defect #3)
Cucumber default run (everything except @mutates): 61 — 52 passed, 9 failed
Cucumber @mutates alone: 5 — 4 passed, 1 failed (defect #1)

Playwright tests: 15
  Passed:  14  (chromium client + admin)
  Failed:   0
  Skipped:  1  (AUTH-017 real sign-in — TEST_USERNAME/TEST_PASSWORD not set)
  Cross-browser (@critical on firefox + webkit): 4 / 4 passed
  Stability: @regression run three times consecutively — 14/14 each time

Smoke:       Cucumber FAIL (2 × defect #3) · Playwright PASS
Regression:  Cucumber FAIL (10 application defects) · Playwright PASS

Database after every run: 0 automation buyers, 0 sessions, 0 orders, 0 roles,
0 orphaned journals or GST rows.
```

The failing Cucumber scenarios assert the **correct** behaviour and are left
failing deliberately. Making them pass would mean either weakening the assertion
to match a defect or changing the application, and neither is this suite's job.

## Application defects found

Ordered by severity. None were changed by this work; each has a one-line fix noted
so the decision is quick.

### 1. Any signed-in shopper can read any other shopper's order — IDOR (security)

`GET /api/master/client/order/{orderCode}` returns 200 for a valid order code
regardless of who owns it. Verified with two independently seeded buyers: buyer B
retrieved buyer A's order in full. Scenario ORD-006 ("Another shopper cannot open
the order").

*Fix:* in `ClientOrderServiceImpl`, compare the order's `customer_id` to the
session's user before returning it (404, not 403, so codes cannot be probed).

### 2. Bean Validation is inert across the whole application

The backend jar contains `jakarta.validation-api-3.0.2.jar` — the annotations —
but no implementation: there is no `hibernate-validator` and no
`spring-boot-starter-validation` in `pom.xml`. `@Valid` on the controllers is
therefore a no-op, and every `@NotBlank`, `@NotEmpty`, `@Min`, `@Email` in the
codebase does nothing.

Observed on the order pipeline: an order with **no items**, with **quantity 0**,
with **quantity −1**, and with **no delivery location** were each accepted with
`200 "Order placed successfully"` and written to `customer_order`. Scenarios
ORD-007, ORD-008 (×2), ORD-009. The same applies to registration, login, address
and every other validated request.

*Fix:* add `spring-boot-starter-validation` to `veloria.backend/pom.xml`. Expect
some existing requests to start being rejected once the annotations take effect
— that is the point, but it warrants a pass over the frontend forms.

### 3. Missing `Authorization` header answers 400, not 401

Client endpoints declare `@RequestHeader("Authorization")` (required), so a
request with no token fails Spring's header binding with
`400 "Required header 'Authorization' is not present"` before the session is
ever checked. A missing credential is a 401. The client app never sends a
tokenless request to these endpoints, so users do not see it; the API contract is
still wrong. Scenarios AUTH-002, CART-001, FAV-001.

*Fix:* `required = false` on those parameters and a null check that raises
`ResponseCode.UNAUTHORIZED`, or a `MissingRequestHeaderException` handler in
`AppExceptionHandler` mapping the `Authorization` header to 401.

### 4. Bag GST preview returns 500 for a shopper with no default address

`GET /client/bag/gst-preview` resolves place of supply from the default address;
with none, `GstIdentityService` throws `IllegalStateException("Place of supply is
unknown …")`, which surfaces as `500 INTERNAL_ERROR`. A brand-new account has no
address, so this is a normal state, not an error. Scenario CART-007n.

*Fix:* catch the unresolved case in `ClientBagServiceImpl.getGstPreview` and
return a preview marked unresolved (or a 4xx with a message), not a 500.

### 5. The September books do not reconcile

`GET /accounting/reconciliation` for 2026-09 reports `failed: 2`:

| Check | Ledger | Source | Difference |
|---|---|---|---|
| Revenue: ledger vs orders | −₹50,573.70 | ₹0.00 | ₹50,573.70 |
| GST payments: ledger vs payments made this period | −₹1,005.00 | ₹1,005.00 | ₹2,010.00 |

Negative ledger revenue in a month with no orders means reversal journals dated
in September against sales that were not. This is not automation data — the
suite leaves nothing behind and the figures are two orders of magnitude larger
than any test order. One candidate worth checking first: the seven sale
journals that were reversed and re-posted on 2026-09-08 to correct the ₹0.05
GST rounding difference; if the reversals carried a September date while the
re-posts carried the original August dates, this is exactly the signature.
Scenario FIN-003. Needs a finance-side look, not a code change.

## Application defect found and fixed during this work

**A dead session bounced a browsing shopper to `/login`** (client app). The
token-refresh code added earlier this session sends the refresh as a POST; when
that POST itself got a 401 (login ended), the interceptor treated it as a failed
user action and hard-redirected. Scenario AUTH-013 caught it on the first
Playwright run. Fixed in `frontend-client/src/services/api.ts`: the refresh
call's own 401 now signs out in place and never redirects; only the request the
user actually made decides that. AUTH-013 passes; the earlier manual check had
only covered the case where the refresh succeeds.

## Automation defects found and fixed

All fixed before the numbers above were taken.

| Defect | Where | Fix |
|---|---|---|
| Role creation asserted 200; the API returns 201 | `roles_permissions.feature`, `RoleSteps` | expect 201 |
| "keeps the original login expiry" compared against the *old* row after refresh had capped it | `AuthenticationSteps`, `DatabaseSteps` | capture the original expiry before refreshing |
| Two order variants never registered cleanup; orders the app wrongly accepted leaked | `OrderSteps` | `captureOrderCode()` on every variant |
| `request.newContext({ baseURL })` + a leading-slash path dropped `/api/master` (404) | `fixtures/index.js` | absolute URLs |
| `₹180` appears twice on the calculator (IGST and Total Tax) — strict-mode violation | `gst.spec.js` | assert the unique grand total; `.first()` on the shared figure |
| Module labels are title case in the DOM; CSS uppercases them | `CreateRolePage`, `roles.spec.js` | title-case matchers |
| Sidebar repeats 13 module names → 32 matches instead of 19 | `CreateRolePage` | scope to `<main>` |
| `afterAll` ended the pg pool per spec file; workers span files | `fixtures/db.js`, `index.js` | idle-timeout pool, never ended explicitly |

One data-handling mistake in the diagnostics, not the suite: comparing naive-UTC
`TIMESTAMP` columns against Postgres `now()` in the local zone made a 60-second
grace look like −19,740 s. Every timestamp check in the suite uses
`timezone('UTC', now())`.

## What was not run, and why

- **AUTH-017** (real sign-in form): requires a real account's credentials in the
  environment. Skipped, not failed.
- **Card payment path**: the checkout journey uses Cash on Delivery. Automating
  card entry would mean holding card details; not done.
- **Playwright Firefox/WebKit** ran only the `@critical` journeys by design
  (`playwright.config.js`); the full admin suite is Chromium-only.

## Reports

- Cucumber: `target/cucumber-report.html`, `target/cucumber.json`, `target/cucumber-junit.xml`
- Playwright: `playwright/playwright-report/index.html` (`npm run report`),
  failure traces and screenshots under `playwright/test-results/`
