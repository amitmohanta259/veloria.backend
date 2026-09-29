# P0-18 — Testing dashboard, defect lifecycle and traceability inventory

> **Built and running.** An authorized engineer opens
> `Admin → Engineering → Testing`, chooses a scope, and presses Run Tests. The
> backend validates the scope against an allowlist, records a run, executes Maven
> or Playwright as a subprocess, parses each framework's own report, and persists
> every individual outcome. Failures can be raised as defects that move through an
> enforced lifecycle and close only against a retest that actually ran.
>
> **What is not built** is the element-by-element test authoring the addendum's
> "zero omission" clause asks for. §9 of this document gives the real numbers
> rather than a claim.

## 1. What the addendum asked for, and what exists

| Requirement | State |
|---|---|
| §2 UI element inventory with stable IDs and traceability | **Built** — 1,170 items discovered from source |
| §5 Defect evidence package | **Built** — 22 fields including cURL, request/response, DB before/after, screenshot path |
| §6 Defect lifecycle with enforced closure | **Built** — closure requires a verified retest |
| §6 Closed defects leave the open list, stay in the register | **Built** |
| §6 Repeat failure reopens rather than duplicates | **Built** — deterministic fingerprint |
| §9 Coverage measured from an enumerated inventory | **Built** — and it reports a low number honestly |
| §10 UI-triggered real execution | **Built** — the critical requirement |
| §10 Execution safety | **Built** — allowlist, no shell, no arbitrary paths |
| §8 Regression runs the complete estate | **Built** — including Cucumber unfiltered |
| §2 Every UI element actually tested | **Not done** — 14 of 750 provably covered |
| §4 Field-level validation matrix for every DTO | **Not done** |
| §3 CRUD lifecycle tests for every entity | **Partially** — existing suites, not per-entity |
| §5 Screenshots attached to defect records | **Field exists, capture not wired** |
| §10B "Investigate and Fix" automation | **Not done** — deliberately |

## 2. Execution model

```
Engineer → Testing dashboard → confirm scope
   → POST /engineering/testing/runs   (202 Accepted, run number)
   → allowlist validation → run recorded QUEUED
   → single-thread executor → ProcessBuilder → Maven / Playwright
   → framework report parsed → results persisted
   → COMPLETED | PARTIAL | FAILED | CANCELLED
   → dashboard polls, shows real counts
```

**202, not 200.** A full regression takes tens of minutes; holding the HTTP
request open would tie a browser to it. The dashboard polls every three seconds
while a run is in flight and stops the moment it settles.

**No fabricated progress.** The bar is indeterminate because the runner genuinely
cannot know how many tests remain until the framework reports. A percentage would
be invented.

**PARTIAL is not FAILED.** A suite that ran and reported failures is `PARTIAL` —
the tests did their job. `FAILED` is reserved for a runner that could not produce
a result at all: a timeout, a missing tool, a crash. Conflating them would make a
real bug look like broken infrastructure.

## 3. Execution safety — why a "run tests" button is not remote shell access

The browser sends three values, each from a closed vocabulary:

```
testType     SMOKE | SANITY | UNIT | REGRESSION | E2E
modules      keys from TestCommandCatalog.MODULES
environment  local | dev          (production is absent, not merely blocked)
```

`TestCommandCatalog` turns those into a command assembled **from constants in
that file**. `ProcessBuilder` receives an argument list, so there is no shell to
inject into. The working directory comes from configuration. The browser cannot
name a command, a path, a flag, a test filter or a file.

Verified against the running service:

```
modules = ["gst; rm -rf /"]   → 400  Unknown module 'gst; rm -rf /'. Allowed: accounting, …
environment = "production"    → 400  not approved for test runs
no token                      → 401
ENGINEERING_ANOMALY_VIEW only → 403 on the run endpoint
```

Two further protections:

- **One run at a time**, enforced by a partial unique index — two suites against
  one database corrupt each other's fixtures. A second caller gets 409.
- **Timeout and cancellation.** A run is killed after a configured limit, and the
  live process handle is kept so Cancel actually stops it.

## 4. Reading results, not counting lines

Each framework's own report is parsed, not its stdout:

| Suite | Source |
|---|---|
| Backend JUnit | `target/surefire-reports/*.xml` |
| Cucumber | `target/cucumber.json` |
| Playwright | the JSON reporter's document on stdout |

Stdout says "564 tests, 0 failures". The report says *which* test failed and with
what message — and without that a failure cannot be attached to a defect, which
is the entire point of collecting it.

The XML parser runs with `disallow-doctype-decl` and entity expansion off. The
reports are ours, but an XML parser with entity resolution enabled is an XXE sink
regardless of who wrote the file.

## 5. Defect lifecycle

```
NEW → CONFIRMED → IN_PROGRESS → FIXED → READY_FOR_RETEST → VERIFIED → CLOSED
                                                  ↑                      │
                                               REOPENED ←────────────────┘
```

Two rules shape it.

**A failure is not automatically a defect.** A test can fail because the product
is wrong, the test is wrong, or the environment is. So raising from a run creates
defects in `NEW` — *suspected* — and a person confirms. Nothing here decides a
root cause. Raising is also a separate action from running, so the register does
not silently fill with test and environment problems.

**Closure requires a retest that actually ran.** `DefectStatus` permits `CLOSED`
only from `VERIFIED`, and `verify()` refuses unless the named run contains the
test the defect was raised from *and* that test passed:

```
Run did not execute this test  → 400  "…cannot verify it. Re-run the suite that covers X."
Test still FAILED in that run  → 409  "…The defect stays open."
```

Changing code and asserting success cannot close a defect.

**A repeat reopens rather than duplicates.** The fingerprint is over suite, class
and test name — deliberately *not* the failure message, which carries run-specific
ids and timestamps and would make every occurrence look new. A failure matching a
closed defect reopens it, increments `reopenCount`, and keeps the original number,
evidence and history.

**Closed defects leave the default list, never the register.** `openOnly=false`
returns the full history with fix date, verification date and retest run.

### Evidence

Twenty-two fields per defect, including reproduction steps, expected and actual
behaviour, a cURL command, request and response bodies, database state before and
after, a screenshot path, root cause, proposed resolution, actual fix, changed
files and the regression test added.

Everything written into an evidence field passes through `DefectService.redact`,
which masks bearer tokens, `password`/`secret`/`token`/`api_key` assignments, AWS
key ids and gateway keys. A defect record is read and exported by people; it must
not carry a working credential.

## 6. Traceability inventory

`TestInventoryService` walks the real source — admin and client React views,
Spring controllers, JPA entities — and records what the test programme is
accountable for. Discovered on this build:

```
UI elements     750
API endpoints   284   (282 after de-duplication)
Routes           55
Entities         83
                ────
Total         1,170 items
```

**Discovery marks everything `UNTESTED`.** Only a run or a person marks otherwise.
An inventory that optimistically assumed coverage would be worse than none,
because it would report a number nobody could trust.

The one automatic link that is defensible: an element carrying a `data-testid`
that a Playwright spec also references is recorded as covered by that spec — the
selector is literally the test's handle on the element. That link is read from the
spec sources, so it is evidence rather than assumption.

Identifiers are derived from what an item *is*, not the order it was found in, so
adding a button to a file does not renumber everything after it and silently
detach it from its recorded result. Rediscovery never resets a status a run
already proved.

## 7. The dashboard

`Admin → Engineering → Testing`:

- Test type selector, **Regression by default**, environment selector, module
  chips (none selected means all)
- Confirmation naming the exact scope before anything runs
- Live run panel: run number, who triggered it, start time, the command label
- Counts: total, passed, failed, skipped, blocked
- Results tab, failures first, with the framework's own failure message
- Defect register with severity, status, first/last seen run, reopen count
- Defect detail with evidence and only the transitions the lifecycle permits
- Coverage tab over the inventory, with a note that the figure is a floor
- Run history — click any run to load its results

## 8. Two defects this work found

Both in code written during this phase, both found by running the thing rather
than reasoning about it.

### The whole application silently became read-only

Adding a second `JdbcTemplate` bean for the read-only scanner made Spring Boot
back off its `@ConditionalOnMissingBean` auto-configuration, so **every**
unqualified `@Autowired JdbcTemplate` in the application resolved to the
read-only one. The full suite returned **178 errors** reading
`ERROR: permission denied for table users`.

The Engineering tests alone had passed. Only the complete suite exposed it.

### A nine-test run reported 564 passes

The first real run through the dashboard asked for the `validation` module —
9 tests — and reported **564 passed in 8.4 seconds**. Maven does not clear
`target/surefire-reports` between runs, so the parser was reading every stale XML
from previous runs and attributing them to this one.

Fixed by recording when each step starts and ignoring report files older than
that. The same filter protects the Cucumber report. Re-run: **9 tests, 9 passed**.

Both runs are preserved in the run history, so the bug and its fix sit next to
each other.

## 9. Coverage — the honest numbers

```
Inventory coverage (items provably linked to a test)

  UI_ELEMENT      14 / 750    1.9%
  API_ENDPOINT     0 / 282    0.0%
  ROUTE            0 /  55    0.0%
  ENTITY           0 /  83    0.0%
```

**These numbers understate reality, and they are still the right ones to publish.**
Cucumber's 87 scenarios and Playwright's 42 tests exercise routes and endpoints
constantly — but through HTTP, without a recorded link back to an inventory item.
Until that link exists, the honest statement is "14 items are provably covered",
not "most things are probably fine".

Code coverage, measured separately with JaCoCo over the 564 JUnit tests:

```
Line 38.9%   Branch 25.6%   Instruction 33.4%   Method 28.6%
```

That figure excludes Cucumber and Playwright, which run against a separately
started JVM that JaCoCo does not instrument.

### The gap between this and "100%"

The addendum asks for every UI element, field and CRUD operation tested. That is
**750 UI elements, 282 endpoints and 83 entities** — a programme of work measured
in weeks, not a single pass. What exists now is the machinery that makes it
tractable and countable: every item enumerated with an identifier, a status, and a
place to record the test that covers it.

Reaching a meaningful number from here is incremental and measurable: add a
`data-testid` and a test, and the item moves from `UNTESTED` to `PASSED` with the
covering spec recorded against it.

## 10. Known limitations

```
NOT DONE   Per-element test authoring for 736 remaining UI elements.
NOT DONE   Field-level validation matrix (§4) — no per-DTO required/format/
           boundary inventory exists. The API_ENDPOINT inventory is the
           starting point for it.
NOT DONE   Per-entity CRUD suites (§3). Existing tests cover CRUD through
           workflows rather than as an enumerated matrix.
NOT WIRED  Screenshot capture. The defect record has screenshot_path, but
           nothing attaches a Playwright screenshot automatically yet.
NOT DONE   "Investigate and Fix" (§10B). Deliberate: automatic code
           modification triggered from a web button needs an authorization
           and review model that does not exist here.
LIMIT      Module selection narrows the backend suite only. Asking for
           "regression on gst" and getting a full browser run would
           misreport the scope, so it is refused for E2E and SMOKE.
LIMIT      One run per instance. Correct for a shared database, but it means
           a long regression blocks a quick smoke run.
LIMIT      Playwright results are parsed from the JSON reporter on stdout. A
           crash before the reporter emits leaves no per-test detail — the
           run is then FAILED with no results rather than falsely green.
NOTE       Runner paths (Maven, npx, Node bin, working directory) are
           configured in the gitignored application-local.yaml. A deployed
           environment must supply its own.
```
