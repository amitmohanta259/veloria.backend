# Veloria — PostgreSQL & E-Commerce Architecture Audit

**Status: audit only. No database or application change has been made.**

Inspected: PostgreSQL 18.3 (`localhost/postgres`), the Spring Boot backend, and both
frontends. 77 tables, 203 indexes, 2 triggers, 33 functions, 0 views, 0 materialized
views, 0 partitions, extensions `pg_trgm` + `plpgsql`.

### A note on measurement, stated up front

This database holds **development data — the largest table has 187 rows**. Every
`EXPLAIN ANALYZE` in this document is therefore a statement about the *access path
the planner chose*, not about production latency. A sequential scan over 187 rows
costs 0.04 ms and is the correct plan at that size.

So: **no production performance numbers are reported, because none were measured.**
Where this document says "bottleneck" it means *a plan shape that degrades
predictably as rows grow*, and it says so explicitly. Nothing here is extrapolated
into an invented millisecond figure.

The serious findings below are **correctness and architecture** findings, which are
visible at any data size — and they matter more than any index.

---

## 1. Current architecture

```
frontend-client (React)        frontend-admin (React)
        │                              │
        └────────── REST ──────────────┘
                     │
         Spring Boot 3.1 monolith, Java 17
         service layer → Spring Data JPA → HikariCP
                     │
              PostgreSQL 18.3
```

- One database, one schema (`public`), no read replica, no partitioning, no caching tier.
- Liquibase migrations (167 changesets), registered through `db/master.yaml`.
- Transaction control is entirely Spring `@Transactional`; default isolation
  (`READ COMMITTED`), no explicit locking anywhere in the codebase.
- No message broker, no outbox, no async workers. The only scheduled job is a daily
  `client_session` purge.

---

## 2. Current database model

### Domains that exist

Users · Authentication (admin JWT + client opaque sessions) · Authorization
(roles/permissions) · Customers · Products · Categories/Collections/Sub-categories ·
Product images · Inventory (per product and per size) · Suppliers · Purchase orders ·
Purchase invoices · Cart/Bag · Favourites · Orders · Order items · Returns ·
Exchanges · GST (HSN, rules, output tax, input tax/ITC, movement ledger, credit and
debit notes, returns, payments, e-invoice, e-way bill, exceptions) · Sales invoices ·
Accounting (chart of accounts, journal entries and lines, periods) · Expenses ·
Payroll · Staff · Audit (GST audit log) · Business details.

### Domains that do **not** exist — do not assume they do

**Customer payments · Payment transactions · Refunds · Settlements · Sellers ·
Marketplace commission · Warehouses · Stock movement ledger · Coupons · Discounts ·
Promotions · Notifications · Shipping/delivery tracking · Product variants (table
exists, 0 rows, unused).**

This matters enormously and is the subject of finding **P0-1**.

### Table classification

| Class | Tables |
|---|---|
| **Master** | `inventory_product`, `inventory_category`/`collection`/`sub_category`, `gst_tax_rules`, `gst_hsn_master`, `gst_state_master`, `chart_of_accounts`, `supplier`, `users`, `staff`, `roles`, `business_details`, `organization`, `gst_registration` |
| **Transactional** | `customer_order`, `customer_order_item`, `sales_order`, `customer_bag`, `customer_favourite`, `order_return_request`/`_item`, `order_exchange_request`/`_item`, `purchase_order`/`_item`, `client_session` |
| **Financial** | `journal_entry`, `journal_entry_line`, `sales_invoice`/`_item`, `purchase_invoice`/`_item`, `gst_output_tax`, `gst_input_tax`, `gst_itc_transaction`, `gst_payment`, `gst_credit_note`/`_item`, `gst_debit_note`/`_item`, `expense`, `salary_payment` |
| **Audit** | `gst_audit_log`, `gst_accounting_exception`, `gst_return_snapshot`, `databasechangelog` |
| **Reporting** | *(none — every dashboard reads transactional tables directly)* |

Strict consistency required: orders, inventory, journals, GST output/input, invoices.
Eventually consistent is acceptable: dashboards, indicators, reporting aggregates.

### What the schema does well

These are real strengths and should not be "optimized" away:

1. **Money is `bigint` paise everywhere** — 188 monetary columns, zero `float`,
   `double` or `numeric`. Rates are basis points. Exactly what §43 asks for, already
   done, consistently.
2. **GST is snapshotted at transaction time.** `customer_order_item` stores
   `hsn_code`, `cgst_rate_bp`, `sgst_rate_bp`, `igst_rate_bp`, the computed amounts,
   `unit_price_paise` and `taxable_value_paise`; `sales_invoice_item` does the same.
   Changing a GST rule tomorrow cannot alter yesterday's invoice. §14, §15, §34, §35
   are satisfied **by design**.
3. **Double-entry accounting is real**, with a `CHECK` that each line is either a
   debit or a credit, never both, and period-lock triggers.
4. **Invoice numbering is atomic** — `UPDATE … SET next_number = next_number + 1 …
   RETURNING next_number - 1`. This is the correct pattern, not a read-modify-write.
5. **20 CHECK constraints** encode genuine invariants (ITC claimed ≤ eligible,
   reversed ≤ claimed, reclaimed ≤ reversed, returned_quantity ≤ quantity,
   discount ≤ gross, non-negative tax components).
6. **Three idempotency indexes** exist, including the important one:
   `ux_journal_source` on `(source_type, source_id) WHERE status='POSTED'` — a posted
   journal cannot be duplicated.

---

## 3. Current e-commerce transaction flows

### Place order — `ClientOrderServiceImpl.placeOrder`, one `@Transactional`

```
validate session → load user by session email
resolve place of supply (address book → state name → PIN prefix)
load products
compute taxable value, CGST/SGST/IGST per line from gst_tax_rules
save customer_order
save customer_order_item[]        ← quantities, GST, prices snapshotted
save sales_order[]
recordSaleMovement → gst_movement_ledger
[failure of GST steps → gst_accounting_exception, order still saved]
COMMIT
```

**What is absent from this flow:** any inventory check, any inventory decrement, any
payment record, any cash posting, any generic audit row.

### Other flows

- **Bag operations** — `@Transactional`, single-table, safe.
- **Journal posting** — `JournalService.post` is the only path to the ledger,
  enforces debits = credits, is idempotent on `(source_type, source_id)`, refuses
  closed periods. Well built.
- **Return request** — creates `order_return_request`/`_item`, sets order status.
  No inventory effect, no refund, no credit note automatically.
- **Restock** — `InventoryProductServiceImpl.addStock`, **not transactional**.

---

## 4. Triggers, functions, procedures

Only **two triggers**, and both are the kind that should exist.

### Trigger audit (§61)

| Trigger | Table | Event | Purpose | Queries | Frequency | Risk | Performance impact | Recommendation |
|---|---|---|---|---|---|---|---|---|
| `trg_journal_period_lock` | `journal_entry` | BEFORE INSERT OR UPDATE, FOR EACH ROW | Reject postings into a CLOSED/LOCKED accounting period | 1 indexed point lookup on `accounting_period` (24 rows, effectively cached) | Once per journal entry — not per line | Low | Negligible; one buffer hit | **KEEP** |
| `trg_gst_movement_period_lock` | `gst_movement_ledger` | BEFORE INSERT OR UPDATE, FOR EACH ROW | Reject movements into a LOCKED/FILED GST period | 1 lookup on `gst_tax_period` (2 rows) | Per movement row — several per order | Low | Negligible today. At bulk-backfill scale this is a per-row lookup that a statement-level check would do once | **KEEP** (revisit only if bulk backfills become routine — see §26) |

Both are enforcing a **database invariant that cannot be expressed as a CHECK**
(it depends on another table's state), they perform no writes, call nothing external,
and fail loudly with `check_violation`. This is exactly the narrow case §13 and §27
permit. No trigger calls an external service (§53 satisfied).

**Recommendation: add no new triggers.** Specifically, do **not** add triggers for
inventory decrement, GST calculation, payment, or refund — all four are explicitly
warned against in §24 and all four are application-service work here.

### Functions (33)

Almost all are Liquibase/extension support plus the two trigger functions. There are
no business-logic stored procedures, and none should be introduced: order placement,
GST resolution and refunds depend on request context, external state and
authorization, which §29 correctly says does not belong in the database.

**One narrow exception worth considering later (P2):** an atomic inventory decrement
could be a small `plpgsql` function, but a plain parameterised `UPDATE … WHERE
available >= n` from the service is simpler and equally atomic. Prefer the UPDATE.

---

## 5. Current indexes

- **203 indexes; 134 unique; 81 have never been scanned.**
- The unused ones are mostly Liquibase-created secondary indexes on low-traffic
  tables. At this data size that proves nothing — an index used only by a monthly
  report legitimately shows zero scans in a dev database. **Do not drop indexes on
  this evidence** (§18 of the absolute rules). Re-evaluate against production
  `pg_stat_user_indexes` after a full business cycle.

### Measured index gaps

These two are not judgement calls — the FK-shaped column has no index at all, and the
planner confirms a sequential scan:

```
EXPLAIN ANALYZE SELECT * FROM journal_entry_line WHERE journal_entry_id = 40;
  Seq Scan on journal_entry_line (actual time=0.012..0.015 rows=4 loops=1)
    Filter: (journal_entry_id = 40)
    Rows Removed by Filter: 183
```

`journal_entry_line` has taken **11,773 sequential scans reading 2,049,693 rows** in
this dev database — every trial balance, P&L and ledger read walks the whole table.
At 187 rows that is 0.04 ms. At 10 M lines it is a table scan per report.

Same shape, same absence of an index:

| Table.column | Role | Seq scans so far | Rows read |
|---|---|---|---|
| `journal_entry_line.journal_entry_id` | parent FK, has `ON DELETE CASCADE` | 11,773 | 2,049,693 |
| `customer_order_item.customer_order_id` | parent FK | 13,544 | 173,053 |
| `gst_output_tax.customer_order_id` | order → tax join | — | — |
| `gst_movement_ledger.source_id` | reconciliation join | 1,321 | 31,184 |
| `sales_invoice_item.sales_invoice_id` | *(has FK, no index)* | — | — |
| `inventory_product_size_stock.product_id` | per-size stock lookup | — | — |

An unindexed FK also makes every parent `DELETE` scan the child table.

---

## 6. Current performance bottlenecks

**Measured, at production scale: none — because production scale was not available.**
I will not claim otherwise.

What *is* measured: the plan shapes above, and that dashboards/reports read
transactional tables directly with no read model (0 views, 0 materialized views).

**Predictable degradation, in the order it will bite:**

1. Ledger reads (trial balance, P&L, reconciliation) — full scans of
   `journal_entry_line`, unindexed, one scan per report.
2. Order history and order detail — unindexed child lookups.
3. GST period reports — `gst_movement_ledger` and `gst_output_tax` scans.
4. Admin dashboard — aggregates computed live over `customer_order`.
5. Product listing — currently fine; `pg_trgm` is installed but **no trigram index
   exists**, so any future `ILIKE '%…%'` search will scan.

---

## 7. Current concurrency risks

| # | Risk | Current protection | Severity |
|---|---|---|---|
| C1 | **Oversell** — two buyers, one unit | **None whatsoever.** Order placement never reads or writes stock | **Critical** |
| C2 | **Lost restock** — `addStock` does read → add → save with no `@Transactional` and no lock; two concurrent restocks lose one | None | **High** |
| C3 | Bag quantity update — read-modify-write inside `@Transactional`, `READ COMMITTED` | Transaction only; last writer wins | Low (per-user data) |
| C4 | Order-code collision — `UUID.randomUUID()` truncated to **8 hex chars** scoped per day | `customer_order_order_code_key` UNIQUE → collision is a hard failure, not a duplicate | Medium (availability, not corruption) |
| C5 | Invoice numbering | Atomic `UPDATE … RETURNING` | **Safe** |
| C6 | Duplicate journal posting | `ux_journal_source` partial unique | **Safe** |
| C7 | Deadlock ordering | Not documented anywhere; order flow touches order → items → movement → journal consistently, so risk is currently low by accident, not by design | Low now, rises with inventory writes |

On C4: 8 hex characters is ~4.3 × 10⁹ values. Collisions follow the birthday bound —
at 100,000 orders/day the probability of at least one collision on a given day is
roughly 1.2%. Each collision is a failed checkout for a real customer.

---

## 8. Current payment risks

**This is the most serious finding in the audit.**

There is **no customer payment domain**. Confirmed three ways: no `payment`,
`payment_transaction`, `refund` or `settlement` table exists; `customer_order` has no
payment column beyond `status`; and no Java code persists a payment method.

The checkout UI (`Payment.tsx`) offers Card, UPI, Cash on Delivery and Partial, sends
the order to `/order/place`, and **the chosen method is discarded**.

Consequences, all of which follow directly:

| §  | Requirement | Reality |
|---|---|---|
| 4 | Payment in vs payment out separated | No payment records at all |
| 5 | Payment state machine | No payment states |
| 6 | Payment idempotency key / unique provider ref | Nothing to be idempotent about |
| 19 | Payment ≠ DB commit | No payment integration exists |
| 21 | Refund idempotency, refund history | No refund table |
| 22 | Financial immutability for payments | N/A |

And in the ledger this is directly visible:

```
Account 1100 Accounts Receivable :  49 lines   Dr ₹79,185.23   Cr ₹59,388.90
Account 1020 Bank                :   1 line    (the GST remittance)
```

**Every customer sale debits Accounts Receivable and nothing ever clears it**, because
collection is never recorded. ₹19,796.33 sits as permanently outstanding receivable.
This is also a partial explanation for the reconciliation FAIL surfaced by the
automation suite last session — *revenue in the ledger with no corresponding
settled cash*.

---

## 9. Current inventory risks

| # | Finding | Detail |
|---|---|---|
| **I1** | **Orders never decrement stock** | `ClientOrderServiceImpl` contains no reference to stock. Unlimited overselling; inventory is decorative on the sell side |
| **I2** | **No inventory state model** | Only `initial_stock`. There is no `available`, `reserved`, `sold`, `damaged`, `returned` or `in_transit` — §7's concepts do not exist |
| **I3** | **No stock movement ledger** | `gst_movement_ledger` tracks *tax* movements, not stock. There is no immutable PURCHASE/SALE/RETURN/DAMAGE/ADJUSTMENT history (§10) |
| **I4** | **No damaged-product functionality** | §11–§13 describe a feature that is not implemented. `order_return_item.return_condition` and a `DAMAGED` verification value exist on returns, but nothing moves stock between states |
| **I5** | **No `CHECK (stock >= 0)`** | Nothing prevents negative stock at the database level |
| **I6** | **`addStock` is unsafe** | Read-modify-write, no transaction, no lock (C2) |
| **I7** | Returns don't restock | Correct by §20 *only if deliberate*; currently it appears to be omission rather than an inspection workflow |

---

## 10. Current GST risks

GST is the **best-built part of this system** and most of §14–§17 is already satisfied.

Verified working: transaction-time snapshots of HSN and rates; intra-state
CGST+SGST vs inter-state IGST driven by resolved place of supply; a movement ledger
with supersede-never-delete; credit and debit notes; period locking by trigger;
exceptions recorded rather than swallowed; `gst_output_tax` written inside the order
transaction.

Remaining risks:

| # | Risk |
|---|---|
| G1 | Place of supply can be **unresolved**; the order still commits and is marked `PENDING_REVIEW`. Correct behaviour (better than guessing), but needs an operational queue — nothing alerts on it |
| G2 | The GST preview endpoint **throws 500** when a new shopper has no address (found by the automation suite) |
| G3 | `gst_output_tax` has no FK to `customer_order` — tax rows can orphan |
| G4 | Reconciliation currently reports **2 FAILs** for 2026-09 (revenue ledger vs orders; GST payments) — an open books issue, unresolved |
| G5 | Returns and refunds have **no GST effect wired through** — a return does not produce a credit note automatically |

---

## 11. Current financial risks

| # | Risk | Severity |
|---|---|---|
| F1 | Receivables never clear (see §8) — the balance sheet overstates assets indefinitely | **Critical** |
| F2 | **13 foreign keys across 77 tables.** `customer_order_item` → `customer_order`, `gst_output_tax` → `customer_order`, `sales_invoice` → `customer_order` all have **no FK**. Financial children can orphan silently | **High** |
| F3 | Reconciliation FAILs unaddressed (G4) | **High** |
| F4 | No generic audit log — `gst_audit_log` covers GST only. Price changes, stock adjustments, order cancellations and admin actions are unaudited (§23) | **High** |
| F5 | Opening balances never posted (carried over from earlier work) — the balance sheet is arithmetically correct but incomplete | Medium |
| F6 | Autovacuum has **never run** on `customer_bag`, `gst_movement_ledger`, `gst_output_tax`; `customer_order_item` last ran 2026-08-12 with 35 dead vs 7 live rows | Medium (bloat) |

---

## 12. Required matrices

### Transaction matrix (§62)

| Operation | Transaction required | Lock required | Idempotency | Audit | Trigger | Constraint |
|---|---|---|---|---|---|---|
| Place order | ✅ exists (one `@Transactional`) | ❌ **missing** — needs inventory row lock or atomic conditional UPDATE | ❌ none — a retried checkout creates a second order | ⚠️ partial (GST exceptions only) | ❌ none, correct | ✅ `order_code` UNIQUE |
| Inventory decrement | ❌ **does not happen** | ❌ | ❌ | ❌ | ❌ correct | ❌ no `CHECK (>= 0)` |
| Damage stock | ❌ **feature absent** | — | — | — | ❌ correct | — |
| Payment | ❌ **domain absent** | — | ❌ **required** | ❌ | ❌ correct | — |
| Refund | ❌ **domain absent** | — | ❌ **required** | ❌ | ❌ correct | — |
| GST output | ✅ inside order transaction | ❌ not needed | ⚠️ via movement supersede | ✅ `gst_audit_log` | ✅ period lock — keep | ✅ several CHECKs |
| Return | ✅ | ❌ | ❌ | ⚠️ | ❌ correct | ✅ `quantity > 0`, `returned ≤ quantity` |
| Seller payout | — | — | — | — | — | **N/A — no sellers** |
| Product price update | ⚠️ single save | ❌ | — | ❌ **none** | ❌ correct | — |
| Journal posting | ✅ | ❌ | ✅ `ux_journal_source` | ✅ | ✅ period lock — keep | ✅ debit XOR credit |

### Data consistency matrix (§63)

| Operation | Table A | Table B | Table C | Atomic today? | Failure recovery today |
|---|---|---|---|---|---|
| Order | `customer_order` | `customer_order_item` | `gst_output_tax`, `gst_movement_ledger` | ✅ one transaction — **but inventory is absent from it entirely** | Rollback; GST sub-steps degrade to `gst_accounting_exception` rather than failing the order (deliberate) |
| Payment | — | — | — | **N/A — tables do not exist** | None |
| Refund | — | — | — | **N/A — tables do not exist** | None |
| Damage | — | — | — | **N/A — feature does not exist** | None |
| GST | `customer_order` | `gst_output_tax` | `sales_invoice` | ⚠️ order+output_tax atomic; invoice issued separately | Exception row + manual replay |
| Ledger | `journal_entry` | `journal_entry_line` | — | ✅ | Idempotent re-post; reversal never deletion |

### Concurrency matrix (§10 of the report spec)

| Operation | Race condition | Current protection | Recommended |
|---|---|---|---|
| Order placement | Oversell | **None** | Atomic `UPDATE inventory SET available = available - $1 WHERE id = $2 AND available >= $1`, check affected rows, fail the transaction if 0 |
| Restock | Lost update | **None** | `@Transactional` + atomic `UPDATE … SET stock = stock + $1` |
| Bag quantity | Lost update | Transaction | Acceptable — per-user data |
| Invoice number | Duplicate number | Atomic UPDATE…RETURNING | **Already correct** |
| Journal posting | Duplicate entry | Partial unique index | **Already correct** |
| Order code | Collision | UNIQUE → hard failure | Widen to 12+ hex chars, or use a sequence |

### Idempotency matrix (§11 of the report spec)

| Operation | Idempotency key | Unique constraint | Retry behaviour today |
|---|---|---|---|
| Journal posting | `(source_type, source_id)` | ✅ `ux_journal_source` | Safe |
| Purchase invoice from PO | `purchase_order_id` | ✅ partial unique | Safe |
| GST exception | `(org, type, source_doc)` | ✅ partial unique | Safe |
| **Place order** | **none** | ❌ | **Duplicate order on client retry** |
| **Payment** | **none** | ❌ | **N/A — domain absent; must be designed in from the start** |
| **Refund** | **none** | ❌ | **N/A — domain absent** |
| Return request | none | ❌ | Duplicate return rows possible |

---

## 13. Recommended architecture

Per §60, each decision names its layer and its justification.

### 13.1 Inventory — **Application service + DB constraint + atomic UPDATE**

Not a trigger (§24: "Inventory decrement — usually No").

```sql
-- state, not a single number
ALTER TABLE inventory_product_size_stock
  ADD COLUMN available_quantity bigint NOT NULL DEFAULT 0,
  ADD COLUMN reserved_quantity  bigint NOT NULL DEFAULT 0,
  ADD COLUMN damaged_quantity   bigint NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_stock_non_negative
    CHECK (available_quantity >= 0 AND reserved_quantity >= 0 AND damaged_quantity >= 0);
```

Decrement inside the existing order transaction:

```sql
UPDATE inventory_product_size_stock
   SET available_quantity = available_quantity - :qty
 WHERE product_id = :pid AND size = :size
   AND available_quantity >= :qty;
-- 0 rows affected  →  throw  →  the whole order transaction rolls back
```

*Why:* atomic, no pessimistic lock, no lock ordering to get wrong, and the `CHECK` is
a second line of defence. *Consistency:* strict. *Concurrency:* one row lock for the
duration of the order transaction — acceptable, and the smallest reliable mechanism
(§8). *Failure:* rollback is complete because inventory joins the existing boundary.

Plus an **immutable `inventory_movement` table** (append-only: PURCHASE, SALE,
RETURN, DAMAGE, ADJUSTMENT, TRANSFER, CANCELLATION) written in the same transaction —
current state and history maintained independently, per §10.

### 13.2 Payments — **new domain, application service + constraints + outbox**

This must be designed, not retrofitted. Minimum shape:

```
payment_transaction   -- immutable, append-only
  id, order_code, direction ENUM(IN, OUT),
  type ENUM(PAYMENT_RECEIVED, PAYMENT_REFUND, EXPENSE_PAYMENT),
  amount_paise bigint, method, status,
  idempotency_key   UNIQUE NOT NULL,      -- §6: DB-level duplicate protection
  provider_reference UNIQUE,              -- webhook de-duplication
  created_at, created_by
```

State machine enforced **in the service**, with the illegal transitions of §5
(`REFUNDED → SUCCESS`, `FAILED → SETTLED`, `CANCELLED → PAID`) rejected there and the
history preserved by appending, never updating (§22).

Then the ledger finally balances: `Dr Cash/Bank — Cr Accounts Receivable` on
collection.

*Why not a trigger:* §24 says payment processing is never a trigger. *External calls:*
strictly outside the DB transaction (§18, §19) — commit, then call the provider, then
reconcile on webhook with the idempotency key.

### 13.3 Referential integrity — **constraints**

Add FKs on the commerce spine, each with a supporting index:
`customer_order_item.customer_order_id`, `gst_output_tax.customer_order_id`,
`sales_invoice.order_id`, `sales_invoice_item.sales_invoice_id`,
`journal_entry_line.journal_entry_id`, `inventory_product_size_stock.product_id`.

*Why:* §27 — constraints before procedural logic. An FK is also the index those joins
need, so this fixes the measured plan shapes at the same time.

### 13.4 Read models — **views now, materialized views only when justified**

Dashboards currently aggregate transactional tables live. A plain view does not make
that faster (§30) — but it does centralise the SQL. Materialized views for daily
sales / monthly GST / financial summary **only** once the queries are measurably
expensive in production, with refresh frequency and staleness agreed (§31), and never
for order or inventory decisions (§32).

### 13.5 Audit — **application service, one generic table**

`audit_log(user, action, entity, entity_id, old_value, new_value, at, request_id)`
covering price changes, stock adjustments, damage, order cancellation, refunds and
admin actions (§23). Written by the service, not a trigger, because it needs the
authenticated actor and request ID — which the database does not have.

### 13.6 Outbox — **defer**

§52 permits it; nothing in the current system needs reliable async events yet (no
notifications, no external integrations live). Introduce it **with** the payment
provider integration, not before. Do not add Kafka.

---

## 14. Implementation plan

### P0 — Correctness. Do these before any optimization.

1. **Inventory decrement on order placement**, with the atomic UPDATE and
   `CHECK (>= 0)` above, inside the existing order transaction. Fixes unlimited
   overselling.
2. **Payment domain**: `payment_transaction` with `idempotency_key UNIQUE`, the
   IN/OUT direction split, and the cash-vs-receivable ledger posting. Until this
   exists the books cannot be right.
3. **Enable Bean Validation** — add `spring-boot-starter-validation`. Today `@Valid`
   is inert (the jar has the API, no implementation), so orders with quantity 0, −1
   or no items are accepted. This is an inventory- and financial-correctness bug, not
   a validation nicety.
4. **Fix the order-read IDOR** — any signed-in shopper can fetch any order by code.
5. **Make `addStock` transactional and atomic.**

### P1 — Integrity and safety

6. FKs + supporting indexes on the commerce spine (13.3).
7. Generic `audit_log`.
8. Widen the order code, or switch to a sequence.
9. Resolve the two reconciliation FAILs for 2026-09.
10. Fix the GST-preview 500 for a shopper with no address.
11. Document lock ordering for the order workflow (§49); review autovacuum settings
    on the never-vacuumed tables.

### P2 — Scale readiness (revisit with production statistics)

12. Trigram index for product search — **only if** `ILIKE` search ships.
13. Read models for dashboards, measured first.
14. Inventory movement ledger completion; damaged-stock workflow if the business
    wants it (§11 describes a feature that does not exist today — confirm it is
    actually wanted before building it).
15. Re-evaluate the 81 unused indexes against **production** statistics.

### P3 — Long horizon

16. Partitioning. **Not now.** §39 is explicit: do not partition merely because a
    table is large. Candidates when they reach tens of millions of rows:
    `journal_entry_line`, `gst_movement_ledger`, `customer_order`, and a future
    `payment_transaction`, by time RANGE.
17. Archiving policy for audit and financial history, driven by statutory retention,
    never by performance (§40).

### Scalability risk by volume (§13 of the report spec)

| Rows | What breaks first |
|---|---|
| **1 M** | Ledger reads — unindexed `journal_entry_line.journal_entry_id` scans on every trial balance and P&L. Dashboard aggregates over `customer_order` |
| **10 M** | Order history and GST period reports; autovacuum falling behind on the never-vacuumed write-heavy tables; `DELETE` on parents scanning unindexed children |
| **100 M+** | Partitioning and archiving become mandatory for `journal_entry_line`, `gst_movement_ledger` and `payment_transaction`; single-writer PostgreSQL contention on the inventory hot row for popular SKUs |

---

## Closing

The **schema fundamentals are good** — integer paise, snapshotted GST, real
double-entry, atomic invoice numbering, restrained and correct trigger use. Several
things §1–§66 asks for are already done properly, and the two existing triggers should
be kept exactly as they are.

The gaps are not performance gaps. They are **missing domains**: inventory is never
decremented, and customer payments do not exist as data at all. No index, view or
partition matters next to those two, and per §59 neither should be touched first.

Nothing in this document has been implemented. Say which P0 item to start with.
