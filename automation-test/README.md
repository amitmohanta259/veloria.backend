# Veloria functional automation

Two layers, deliberately split by what each is good at:

| Layer | Stack | Verifies | Where |
|---|---|---|---|
| Business behaviour, API, database | Cucumber/Gherkin · JUnit 5 · Java 17 | HTTP status and bodies, business rules, what the tables hold | `src/test/` |
| Browser behaviour, critical journeys | Playwright · JavaScript | what a person sees and can do | `playwright/` |

Most scenarios run below the UI. The browser suite carries only what the browser
can prove: sign-in state in the header, redirects, the full checkout journey.
Nothing is duplicated across the two.

Traceability: `docs/functional-test-cases.md` (TC IDs) → `docs/coverage-matrix.md`
(which TC is automated where) → `docs/execution-report.md` (what actually happened).

## Prerequisites

- The backend on `http://localhost:8081` with the `local` profile, the client app on
  `:5174` and the admin app on `:5173` (`preview_start` / `npm run dev`).
- PostgreSQL reachable with the same credentials the backend uses.
- Java 17+, Maven, Node 18+.

Everything is configurable — see *Environment* below.

## Running the Cucumber suite

```bash
cd veloria.backend/automation-test
mvn test                                        # default: everything except @mutates
mvn test -Dcucumber.filter.tags="@smoke"
mvn test -Dcucumber.filter.tags="@regression"   # includes @mutates — see below
mvn test -Dcucumber.filter.tags="@gst"          # any module tag: @order @cart @admin @financial …
mvn test -Dcucumber.filter.tags="@api and not @database"
```

Reports land in `target/`: `cucumber-report.html` (feature → scenario → step,
status, duration, failure reason), `cucumber.json`, `cucumber-junit.xml` for CI.

## Running the Playwright suite

```bash
cd veloria.backend/automation-test/playwright
npm install                    # one-off; browsers come from the Playwright cache
npm test                       # chromium, client + admin
npm run test:smoke
npm run test:regression
npm run test:cross-browser     # firefox + webkit run the @critical journeys only
npm run report                 # opens playwright-report/index.html
```

Failure artefacts only: a trace and a screenshot per failed test under
`test-results/`. No video, no artefacts for passing tests.

## How tests sign in — and why no test holds a password

The client authenticates with an opaque session token stored in `client_session`.
Test setup **seeds that row directly** (`TestData.seedSession` / `fixtures/db.js`)
for a buyer it also seeds, then — in the browser — plants the token in
`localStorage` exactly as the real login does. That gives an authenticated user
with no credentials anywhere in the repository, and lets a test fabricate a
lapsed or ended session precisely by setting the row's lifetimes.

Admin GST screens sign themselves in through the dev-only `/dev-auth/token`
issuer, which exists under the `local` profile only.

The one journey that genuinely exercises the login form (AUTH-017) reads
`TEST_USERNAME` / `TEST_PASSWORD` from the environment and is **skipped** when they
are absent. Never commit values for them.

## `@mutates` — scenarios that leave business records behind

Placing an order writes to `customer_order`, its items, the GST output subledger,
the movement ledger, a sales invoice and a journal entry. The application never
deletes financial records — that is a product rule. The fixtures remove the
whole graph they created (`TestData.removeOrderGraph`, `db.removeOrdersOf`), and
every run here ended with zero automation rows, but:

- `@mutates` scenarios are **excluded from the default run and from `@smoke`**.
- They are part of `@regression`. **Run `@regression` against a disposable
  database**, never a shared or production-like one.

Seeded rows are recognisable: buyers are `*@automation.veloria.test`, roles carry
the designation `Automation Analyst`.

## Environment

| Variable | Default | Used by |
|---|---|---|
| `VELORIA_API_URL` | `http://localhost:8081/api/master` | Cucumber |
| `VELORIA_DB_URL` | `jdbc:postgresql://localhost:5432/postgres` | Cucumber |
| `VELORIA_DB_USER` / `VELORIA_DB_PASSWORD` | `postgres` / `postgres` | both |
| `API_URL` | `http://localhost:8081/api/master` | Playwright |
| `CLIENT_URL` / `ADMIN_URL` | `http://localhost:5174` / `:5173` | Playwright |
| `VELORIA_DB_HOST` / `PORT` / `NAME` | `localhost` / `5432` / `postgres` | Playwright |
| `TEST_USERNAME` / `TEST_PASSWORD` | *(unset — journey skipped)* | Playwright |

Cucumber defaults live in `src/test/resources/automation.properties`; Playwright's
in `playwright/utils/env.js`. Environment variables override both.

## Layout

```
automation-test/
├── pom.xml
├── docs/                      test cases · coverage matrix · execution report
├── src/test/java/com/veloria/automation/
│   ├── RunCucumberTest.java   JUnit Platform suite → Cucumber engine
│   ├── support/               Config · ScenarioContext (per-scenario state) · Hooks (cleanup)
│   ├── api/                   Http + one client per area (Bag, Order, Gst, Roles, …)
│   ├── db/                    Db (JDBC) · TestData (seed + remove)
│   └── steps/                 one class per area; shared state only via ScenarioContext
├── src/test/resources/
│   ├── features/              authentication · product · cart · order · gst · return · admin · financial
│   ├── junit-platform.properties
│   └── automation.properties
└── playwright/
    ├── playwright.config.js   projects: chromium, chromium-admin, firefox, webkit
    ├── fixtures/              index.js (buyer · session · signIn · api · aProduct) · db.js
    ├── pages/client · pages/admin
    ├── tests/client · tests/admin
    ├── test-data/             addresses · gst
    └── utils/env.js
```

## Tags

`@smoke` `@regression` `@api` `@database` `@security` `@negative` `@boundary`
`@mutates` and one module tag per feature: `@authentication` `@product` `@cart`
`@order` `@gst` `@return` `@admin` `@financial`. Playwright uses `@smoke`,
`@regression`, `@critical` (cross-browser), `@mutates`, `@security`, `@gst`, `@admin`.

## CI

Run the layers as separate jobs so a browser failure never hides an API one:

```bash
# job: api-regression (needs backend + disposable DB)
cd automation-test && mvn test -Dcucumber.filter.tags="@regression"
# job: ui-regression (needs backend + both frontends)
cd automation-test/playwright && npm ci && npx playwright test --grep @regression
# job: ui-cross-browser
cd automation-test/playwright && npm run test:cross-browser
```

Both layers exit non-zero on any failure. Retries are `1` in CI and `0` locally;
a test that needs a retry is a test to fix.

## Adding a scenario

1. Add the case to `docs/functional-test-cases.md` with a TC ID.
2. Write the Gherkin in the matching feature; reuse existing steps first.
3. Put a new step in the class for its area; talk to other steps only through
   `ScenarioContext`; register anything you seed with `ctx.onCleanup`.
4. Tag it, including `@mutates` if it leaves business records behind.
5. Update the coverage matrix.
