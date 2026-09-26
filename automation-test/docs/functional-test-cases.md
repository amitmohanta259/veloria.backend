# Veloria — Functional Test Cases

Scope: functionality that exists in the codebase today. Every case below names the
layer it is verified at (API, DB, UI) and is traced to automation in
`coverage-matrix.md`. Cases that cannot be automated without real credentials or
external systems are marked MANUAL/BLOCKED there rather than faked.

Conventions
- Money is integer paise everywhere; rates are basis points (900 = 9 %).
- "Client" = the shopper-facing app (`frontend-client`, `/api/master/client/**`).
- "Admin" = the management portal (`frontend-admin`, `/api/master/**`).
- A *seeded session* is a `client_session` row inserted by test setup; it grants an
  authenticated client token without a login form or a password.
- A *GST admin token* is issued by the dev-only `/api/master/dev-auth/token`.

---

## AUTH — Authentication and session

| TC | Title | Layer | Preconditions | Steps | Expected |
|---|---|---|---|---|---|
| AUTH-001 | Wrong password is rejected with a message | API | none | POST `/client/login/password` with unknown email + any password | HTTP 401; body message "Invalid credentials." |
| AUTH-002 | Protected endpoint without a token is rejected | API | none | GET `/client/bag` with no Authorization | HTTP 401 |
| AUTH-003 | Unknown / stale token is rejected as 401, not 400 | API | none | GET `/client/bag` with a made-up bearer | HTTP 401 |
| AUTH-004 | Fresh session token is accepted | API, DB | seeded session | GET `/client/bag` | HTTP 200 |
| AUTH-005 | Refresh issues a different token that works immediately | API | seeded session | POST `/client/session/refresh`; GET `/client/bag` with the new token | 200 with `token` ≠ old; new token → 200 |
| AUTH-006 | Old token keeps working through the grace period | API, DB | after AUTH-005 | GET `/client/bag` with the old token; read row | 200; DB `access_expiry` and `expiry` ≤ now + 60 s |
| AUTH-007 | Refresh does not extend the login | DB | after AUTH-005 | compare new row `expiry` to original | equal |
| AUTH-008 | Old token is dead after grace | API | old row expired by fixture | GET `/client/bag`, POST refresh with old token | 401, 401 |
| AUTH-009 | Idle user whose access window lapsed can still refresh | API | row with `access_expiry` in the past, `expiry` in the future | GET `/client/bag` → POST refresh → GET with new token | 401 → 200 → 200 |
| AUTH-010 | Ended login cannot refresh | API | row with `expiry` in the past | POST refresh | 401 |
| AUTH-011 | Public browsing needs no login | API | none | GET `/client/products/new-in`, `/client/categories` | 200 |
| AUTH-012 | Public browsing is unaffected by a stale token | API | none | same as AUTH-011 with a stale bearer | 200 |
| AUTH-013 | Expired session: header shows Sign in, browsing continues | UI | stale token planted in localStorage | open `/` | stays on `/`, "Sign in" button visible, no "My account" |
| AUTH-014 | Expired session: order history redirects to login | UI | stale token planted | open `/order-history` | lands on `/login` |
| AUTH-015 | Expired session: acting (favourite / Buy Now) goes to login | UI | stale token planted | open a product; click Buy Now | lands on `/login` |
| AUTH-016 | Lapsed token is rotated transparently on load | UI, API | lapsed-access session planted | open `/` | one `POST /session/refresh` 200, background reads replayed 200, token in storage changed, "My account" visible |
| AUTH-017 | Real login journey (password) | UI | `TEST_USERNAME`/`TEST_PASSWORD` in env | login form → submit | lands on `/`, "My account" visible |
| AUTH-018 | GST endpoints require VIEW_GST | API | none | GET `/gst/rules` without token | 401 |
| AUTH-019 | GST admin token grants access | API | dev-auth token | GET `/gst/rules` | 200 |

## PROD — Product catalogue (client)

| TC | Title | Layer | Preconditions | Steps | Expected |
|---|---|---|---|---|---|
| PROD-001 | New-in listing returns products | API | catalogue has products | GET `/client/products/new-in` | 200; non-empty list; each has `uuid`, `name`, price |
| PROD-002 | Popular listing is paged | API | — | GET `/client/products/popular?page=0&size=20` | 200; ≤ 20 items |
| PROD-003 | Product detail by uuid | API | a product exists | GET `/client/products/{uuid}` | 200; `uuid` matches; price present |
| PROD-004 | Unknown product uuid | API | — | GET `/client/products/{random uuid}` | not 200; error body has a message |
| PROD-005 | Categories list | API | — | GET `/client/categories` | 200; non-empty |
| PROD-006 | Product sizes | API | product with sizes | GET `/client/products/{uuid}/sizes` | 200 |
| PROD-007 | Collection page renders products without login | UI | — | open `/collection` | product cards visible |
| PROD-008 | Product page renders and shows Buy Now | UI | — | open `/product/{uuid}` | name/price visible, Buy Now button present |

## CART — Shopping bag (client)

| TC | Title | Layer | Preconditions | Steps | Expected |
|---|---|---|---|---|---|
| CART-001 | Add to bag requires a session | API | none | POST `/client/bag/add` no token | 401 |
| CART-002 | Add a product to the bag | API | seeded session, product | POST add → GET bag | bag contains the product with quantity 1 |
| CART-003 | Update quantity (data-driven) | API | item in bag | PUT `/client/bag/{uuid}/quantity?quantity=<q>` | q ≥ 1 → quantity = q; q ≤ 0 → item removed (archived) |
| CART-004 | Remove an item | API | item in bag | DELETE `/client/bag/{uuid}` | bag no longer contains it |
| CART-005 | Update quantity of an item not in the bag | API | seeded session | PUT quantity for a product not in bag | not 200; message "Bag item not found." |
| CART-006 | Bag is isolated per user | API | two seeded sessions | add for user A; GET bag as user B | B's bag does not contain A's item |
| CART-007 | GST preview for the bag | API | item in bag | GET `/client/bag/gst-preview` | 200 |

## FAV — Favourites (client)

| TC | Title | Layer | Preconditions | Steps | Expected |
|---|---|---|---|---|---|
| FAV-001 | Favourites require a session | API | none | GET `/client/favourites` | 401 |
| FAV-002 | Add and list a favourite | API | seeded session | POST add → GET `/favourites/uuids` | contains uuid |
| FAV-003 | Remove a favourite | API | after FAV-002 | DELETE `/favourites/{uuid}` → GET uuids | not contained |

## ORD — Orders (client)

| TC | Title | Layer | Preconditions | Steps | Expected |
|---|---|---|---|---|---|
| ORD-001 | Place an order (critical flow) | API, DB | seeded user + session; delivery location with a state name | POST `/client/order/place` | 200; `orderCode` returned |
| ORD-002 | Order rows are persisted | DB | after ORD-001 | read `customer_order`, `customer_order_item` | one order, N items matching the request quantities |
| ORD-003 | GST is recorded for the order | DB | after ORD-001 | read `gst_output_tax` for the order code | rows exist; CGST+SGST or IGST consistent with `interState` |
| ORD-004 | Order appears in history | API | after ORD-001 | GET `/client/order/history` | contains the order code |
| ORD-005 | Order detail by code | API | after ORD-001 | GET `/client/order/{code}` | 200; items and totals present |
| ORD-006 | Another user cannot read the order | API | second seeded session | GET `/client/order/{code}` as user B | not 200 |
| ORD-007 | Order with no items is rejected | API | seeded user | POST place with `items: []` | 400 |
| ORD-008 | Quantity below 1 is rejected | API | seeded user | POST place with quantity 0 | 400 ("quantity must be at least 1") |
| ORD-009 | Missing delivery location is rejected | API | seeded user | POST place without `deliveryLocation` | 400 |
| ORD-010 | Return on a non-delivered order is refused | API | order not DELIVERED | POST `/client/order/{code}/return` | 400 "Only delivered orders can be returned" |
| ORD-011 | E2E: browse → product → Buy Now → bag → checkout → confirmation → history | UI | authenticated session | as titled | each stage's state visible; order code shown in history |

## GST — Tax engine (admin)

| TC | Title | Layer | Preconditions | Steps | Expected |
|---|---|---|---|---|---|
| GST-001 | Intra-state supply splits CGST/SGST, no IGST | API | rules seeded | POST `/gst/calculate` buyer = seller | `cgstAmount` = `sgstAmount` > 0, `igstAmount` 0, `interState` false |
| GST-002 | Inter-state supply is IGST only | API | rules seeded | buyer ≠ seller | `igstAmount` > 0, CGST/SGST 0, `interState` true |
| GST-003 | Rate by HSN chapter and price band (data-driven) | API | 8 active rules | calculate for each (hsn, price) | rates match the configured rule |
| GST-004 | Price band boundary at ₹1,000 | API | rules for 61/62/63 | ₹999.99 vs ₹1,000.00 | 5 % vs 12 % total |
| GST-005 | Total tax = CGST + SGST + IGST | API | — | any calculate | arithmetic holds |
| GST-006 | Rules listing | API | GST token | GET `/gst/rules` | 200; ≥ 8 active |
| GST-007 | HSN search | API | GST token | GET `/gst/hsn/search` | 200; non-empty |
| GST-008 | Admin GST Management page lists rules | UI | — | open `/admin/gst` | rules table rows visible (e.g. 4202) |
| GST-009 | Admin calculator computes IGST for an inter-state case | UI | — | Calculator tab; fill 6211 / 1500 / 27 / 29 | shows IGST 12 %, ₹180, total ₹1,680 |

## RET — Returns (admin)

| TC | Title | Layer | Preconditions | Steps | Expected |
|---|---|---|---|---|---|
| RET-001 | Returns endpoints require VIEW_GST | API | none | GET `/returns/stats` | 401 |
| RET-002 | Returns stats are internally consistent | API | GST token | GET stats, requests | `totalReturns` = count of requests with a return; rate = returns/orders |
| RET-003 | Returns page shows data when signed in to GST | UI | — | open `/admin/returns` | metrics populated (not "—"), requests table non-empty |

## ROLE — Roles and permissions (admin)

| TC | Title | Layer | Preconditions | Steps | Expected |
|---|---|---|---|---|---|
| ROLE-001 | Every role reports all 19 modules | API | roles exist | GET `/roles` | each `permissions` has 19 entries incl. GST_MANAGEMENT, GST_ACCOUNTING, GST_TRACKER, GST_COMPLIANCE |
| ROLE-002 | Create a role with new-module permissions and read them back | API, DB | — | POST `/roles` with GST_COMPLIANCE view → GET by uuid | grant persisted and returned; `role_permission` row exists |
| ROLE-003 | Update replaces permissions without duplicates | API, DB | after ROLE-002 | PUT with a different set → GET | exactly the new set; no duplicate module rows |
| ROLE-004 | Delete a role removes its permissions | API, DB | after ROLE-003 | DELETE → GET | 404-ish; no `role_permission` rows |
| ROLE-005 | FINANCE is an accepted department | API | — | GET `/staff/list?department=FINANCE` | 200 (was 400) |
| ROLE-006 | Create-role page lists 19 modules and Finance | UI | — | open `/admin/settings/roles/create` | 19 module rows; Finance in department select |
| ROLE-007 | Roles table shows real grant counts | UI | roles with grants | open `/admin/settings` | "n / 19 modules" with n > 0 for a granted role |

## FIN — Financials and accounting (admin)

| TC | Title | Layer | Preconditions | Steps | Expected |
|---|---|---|---|---|---|
| FIN-001 | Dashboard summary responds with stats | API | — | GET `/dashboard/summary` | 200; `totalOrders` ≥ `activeOrders` ≥ 0 |
| FIN-002 | Trial balance balances | API | ledger has postings | GET `/accounting/trial-balance` | total debits = total credits |
| FIN-003 | Reconciliation reports no FAIL | API | — | GET reconciliation | every check PASS or WARNING, none FAIL |
| FIN-004 | Indicators refuse ratios against non-positive denominators | API | — | GET `/indicators?period=` | any `NOT_MEANINGFUL`/`NOT_AVAILABLE` has `value` null |
| FIN-005 | Admin dashboard renders live figures | UI | backend up | open `/admin/dashboard` | Total Orders is a number, not "—" |
| FIN-006 | Debit notes section loads | UI | GST session | open `/admin/gst-compliance?section=debitNotes` | no "COULD NOT LOAD"; table or empty-state rendered |
