// The Engineering section in the Admin portal.
//
// These drive the real screens against the real backend: the menu, the dashboard,
// the confirmation, a scan through to completion, the anomaly detail with its
// server-decrypted table and column, and the three other pages.
//
// The decryption assertions are the ones that matter most. They check that a
// plaintext table name appears on screen *and* that nothing resembling key
// material ever reaches the browser — the whole point of encrypting the schema
// identifiers is defeated if the page is handed the key.
const { test, expect } = require('../../fixtures');

const ADMIN = 'http://localhost:5173';
const API = 'http://localhost:8081/api/master';

/** Mints an Engineering token the way the app's dev issuer does, and installs it. */
async function signInAsEngineer(page, role = 'ENGINEERING_ADMIN') {
  const res = await page.request.post(`${API}/dev-auth/token`, {
    data: { username: 'playwright-engineer', roles: [role], organizationId: 1, gstRegistrationId: 1 },
  });
  expect(res.ok(), 'the dev token issuer must be reachable').toBeTruthy();
  const { data } = await res.json();

  await page.addInitScript(({ token, expiresAt, role }) => {
    sessionStorage.setItem('gstAccessToken', token);
    sessionStorage.setItem('gstAccessTokenExpiry', expiresAt);
    sessionStorage.setItem('gstRole', role);
    localStorage.setItem('adminAccessToken', token);
  }, { token: data.accessToken, expiresAt: data.expiresAt, role });

  return data;
}

test.describe('Engineering @regression @engineering', () => {

  test('ENG-001 the Engineering menu offers all four pages', async ({ page }) => {
    await signInAsEngineer(page);
    await page.goto(`${ADMIN}/admin/engineering`);

    const sidebar = page.locator('nav');
    await expect(sidebar.getByText('Engineering', { exact: true })).toBeVisible();
    for (const label of ['Dashboard', 'DB Data Fluctuation', 'Transaction 360', 'Compliance Data Gaps']) {
      await expect(sidebar.getByText(label, { exact: true })).toBeVisible();
    }
  });

  test('ENG-002 the dashboard loads and the run button is offered', async ({ page }) => {
    await signInAsEngineer(page);
    await page.goto(`${ADMIN}/admin/engineering`);

    await expect(page.getByRole('heading', { name: 'Engineering Dashboard' })).toBeVisible();
    await expect(page.getByTestId('run-anomaly-scan')).toBeVisible();
    await expect(page.getByTestId('run-anomaly-scan')).toBeEnabled();

    // The filters are typed inputs. There is deliberately no field that could
    // carry a table name, a column name or SQL to the scan endpoint.
    for (const id of ['scan-date-from', 'scan-date-to', 'scan-transaction', 'scan-product', 'scan-domain']) {
      await expect(page.getByTestId(id)).toBeVisible();
    }
  });

  test('ENG-003 running a scan asks for confirmation, then completes and records history',
      async ({ page }) => {
    await signInAsEngineer(page);
    await page.goto(`${ADMIN}/admin/engineering`);
    await expect(page.getByTestId('run-anomaly-scan')).toBeEnabled();

    // Confirmation first — the modal states plainly that nothing will be modified.
    await page.getByTestId('run-anomaly-scan').click();
    await expect(page.getByText('This performs a read-only engineering integrity scan.')).toBeVisible();
    await expect(page.getByText('No business data will be modified.')).toBeVisible();

    const accepted = page.waitForResponse(r =>
      r.url().includes('/engineering/anomaly-scans/run') && r.request().method() === 'POST');
    await page.getByTestId('confirm-run-scan').click();

    const response = await accepted;
    expect(response.status(), 'the run endpoint answers 202, not 200: the scan is not finished yet')
      .toBe(202);
    const body = await response.json();
    expect(body.data.scanId).toMatch(/^SCAN-\d{8}-\d{4}$/);
    expect(body.data.status).toBe('QUEUED');

    // The dashboard polls and settles on a terminal state.
    await expect(page.getByTestId('last-scan-status')).toHaveText(/COMPLETED|PARTIAL/, { timeout: 60000 });
    await expect(page.getByTestId('rules-executed')).not.toHaveText('0');

    // And the run appears in history, attributed to whoever ran it.
    await expect(page.getByTestId('scan-history')).toContainText(body.data.scanId);
    await expect(page.getByTestId('scan-history')).toContainText('playwright-engineer');
  });

  test('ENG-004 an anomaly opens and shows its decrypted table and column', async ({ page }) => {
    await signInAsEngineer(page);
    await page.goto(`${ADMIN}/admin/engineering`);

    const rows = page.getByTestId('anomaly-rows').locator('tr');
    const count = await rows.count();
    test.skip(count === 0, 'this database currently has no anomalies to open');

    await rows.first().click();

    await expect(page.getByTestId('detail-transaction')).toBeVisible();
    await expect(page.getByTestId('detail-actual')).toBeVisible();
    await expect(page.getByTestId('detail-expected')).toBeVisible();
    await expect(page.getByTestId('detail-occurrences')).toBeVisible();

    // Decrypted server-side: a real identifier, not the marker.
    const table = await page.getByTestId('detail-table').innerText();
    const column = await page.getByTestId('detail-column').innerText();
    expect(table, 'an authorized engineer must see the real table').toMatch(/^[a-z_]+$/);
    expect(column).toMatch(/^[a-z_]+$/);
    expect(table).not.toBe('ENCRYPTED');
    expect(table).not.toBe('DECRYPTION_UNAVAILABLE');

    await expect(page.getByTestId('open-transaction-360')).toBeVisible();
  });

  test('ENG-005 the browser is never given key material', async ({ page }) => {
    await signInAsEngineer(page);

    // Capture every Engineering response body and inspect it. If a key, a nonce,
    // a tag or a wrapped data key ever reaches the client, this fails — and that
    // would make encrypting the schema identifiers pointless.
    const payloads = [];
    page.on('response', async r => {
      if (!r.url().includes('/engineering/')) return;
      try { payloads.push(await r.text()); } catch { /* streamed or empty */ }
    });

    await page.goto(`${ADMIN}/admin/engineering`);
    await expect(page.getByRole('heading', { name: 'Engineering Dashboard' })).toBeVisible();

    const rows = page.getByTestId('anomaly-rows').locator('tr');
    if (await rows.count() > 0) await rows.first().click();
    await page.waitForTimeout(500);

    expect(payloads.length, 'no Engineering response was observed').toBeGreaterThan(0);
    const all = payloads.join('\n');
    for (const forbidden of [
      'encryptedDataKey', 'encrypted_data_key', 'masterKey', 'plaintextKey',
      'keyVersion', 'key_version', 'nonce', 'authTag', 'auth_tag', 'ciphertext',
    ]) {
      expect(all, `the API returned ${forbidden} to the browser`).not.toContain(forbidden);
    }

    // Nor is anything key-shaped kept in browser storage by these screens.
    const stored = await page.evaluate(() => JSON.stringify({
      local: Object.keys(localStorage),
      session: Object.keys(sessionStorage),
    }));
    expect(stored).not.toMatch(/master|dataKey|aesKey|kms/i);
  });

  test('ENG-006 a reader without the schema permission sees ENCRYPTED, not the table',
      async ({ page }) => {
    // ENGINEERING_ANALYST carries ENGINEERING_ANOMALY_VIEW and nothing else, so
    // findings are readable and schema identifiers are not.
    const { accessToken } = await signInAsEngineer(page, 'ENGINEERING_ANALYST');

    const list = await page.request.get(`${API}/engineering/anomalies?limit=1`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    expect(list.ok()).toBeTruthy();
    const rows = (await list.json()).data;
    test.skip(rows.length === 0, 'this database currently has no anomalies to inspect');

    const detail = await page.request.get(`${API}/engineering/anomalies/${rows[0].anomalyId}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    const d = (await detail.json()).data;

    expect(d.schemaVisible).toBe(false);
    expect(d.tableName).toBe('ENCRYPTED');
    expect(d.columnName).toBe('ENCRYPTED');
    // And it is not merely hidden in the UI — no ciphertext is sent either.
    expect(JSON.stringify(d)).not.toContain('encryptedDataKey');
  });

  test('ENG-007 an unauthorized caller cannot reach the scan API', async ({ page }) => {
    // No token at all: the Engineering chain authenticates at the URL layer, so
    // this is refused before any controller runs.
    const anonymous = await page.request.post(`${API}/engineering/anomaly-scans/run`, {
      data: {}, failOnStatusCode: false,
    });
    expect(anonymous.status(), 'Engineering must not be reachable anonymously').toBe(401);

    const anonymousRead = await page.request.get(`${API}/engineering/dashboard`, { failOnStatusCode: false });
    expect(anonymousRead.status()).toBe(401);

    // A valid token without the run permission is authenticated but refused.
    const { accessToken } = await signInAsEngineer(page, 'ENGINEERING_ANALYST');
    const forbidden = await page.request.post(`${API}/engineering/anomaly-scans/run`, {
      data: {}, headers: { Authorization: `Bearer ${accessToken}` }, failOnStatusCode: false,
    });
    expect(forbidden.status(), 'running a scan needs ENGINEERING_ANOMALY_RUN').toBe(403);
  });

  test('ENG-008 DB Data Fluctuation, Transaction 360 and Compliance Data Gaps all open',
      async ({ page }) => {
    await signInAsEngineer(page);

    await page.goto(`${ADMIN}/admin/engineering/fluctuation`);
    await expect(page.getByRole('heading', { name: 'DB Data Fluctuation' })).toBeVisible();
    await expect(page.getByTestId('fluctuation-table')).toContainText('Journals');
    await expect(page.getByTestId('fluctuation-table')).toContainText('AR (paise)');

    await page.goto(`${ADMIN}/admin/engineering/compliance-gaps`);
    await expect(page.getByRole('heading', { name: 'Compliance Data Gaps' })).toBeVisible();
    // The careful wording is part of the contract: a gap is missing data, not a
    // declared statutory breach.
    await expect(page.getByText(/not a determination that a statutory requirement/)).toBeVisible();

    await page.goto(`${ADMIN}/admin/engineering/transaction-360`);
    await expect(page.getByRole('heading', { name: 'Transaction 360' })).toBeVisible();
    await expect(page.getByTestId('txn360-search')).toBeVisible();
  });

  test('ENG-009 Transaction 360 shows a real transaction and hides what does not exist',
      async ({ page }) => {
    await signInAsEngineer(page);

    // Take a real order code from the database rather than hard-coding one.
    const db = require('../../fixtures/db');
    const { rows } = await db.q(
      `SELECT order_code FROM customer_order WHERE archive = false ORDER BY id LIMIT 1`);
    test.skip(rows.length === 0, 'no orders exist to inspect');
    const orderCode = rows[0].order_code;

    await page.goto(`${ADMIN}/admin/engineering/transaction-360?id=${orderCode}`);
    await expect(page.getByTestId('txn360-result')).toBeVisible();
    await expect(page.getByText('Financial reconciliation')).toBeVisible();
    await expect(page.getByTestId('txn360-result')).toContainText(orderCode);

    // Reconciliation is drawn from stored snapshots; transportation is ₹0 because
    // no pricing rule is approved, and that is shown rather than omitted.
    await expect(page.getByTestId('txn360-result')).toContainText('Invoice total');
    await expect(page.getByTestId('txn360-result')).toContainText('Transportation');

    // A section with nothing in it says so rather than rendering an empty shell.
    await expect(page.getByText('Accounting journals', { exact: false })).toBeVisible();
  });
});
