// The Testing Dashboard — the button that runs the suites.
//
// The important assertions here are the ones about what the button CANNOT do.
// A control that executes server-side commands is worth testing for refusal more
// than for success: an injected module name must be rejected, production must not
// be selectable, an unauthenticated caller must not reach the endpoint, and a
// second run must not start while one is going.
//
// The run these tests start is deliberately the narrowest one available (UNIT on
// a single module) so the suite stays fast and does not re-run itself.
const { test, expect } = require('../../fixtures');

const ADMIN = 'http://localhost:5173';
const API = 'http://localhost:8081/api/master';

async function signInAsEngineer(page, role = 'ENGINEERING_ADMIN') {
  const res = await page.request.post(`${API}/dev-auth/token`, {
    data: { username: 'playwright-qa', roles: [role], organizationId: 1, gstRegistrationId: 1 },
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

test.describe('Testing dashboard @regression @engineering', () => {

  test('TEST-001 the dashboard offers the selectors, with Regression by default',
      async ({ page }) => {
    await signInAsEngineer(page);
    await page.goto(`${ADMIN}/admin/engineering/testing`);

    await expect(page.getByRole('heading', { name: 'Testing' })).toBeVisible();
    await expect(page.getByTestId('run-tests')).toBeVisible();

    // Regression is the default, as the spec requires.
    await expect(page.getByTestId('test-type')).toHaveValue('REGRESSION');

    // Environments are a closed list, and production is not in it.
    const environments = await page.getByTestId('test-environment')
      .locator('option').allTextContents();
    expect(environments).not.toContain('production');
    expect(environments).toContain('local');

    // The chips are uppercased by CSS, so the DOM text is the lowercase key the
    // API actually accepts — asserting the rendered case would test the stylesheet.
    await expect(page.getByTestId('module-selector')).toContainText('gst');
    await expect(page.getByTestId('module-selector')).toContainText('accounting');
  });

  test('TEST-002 running asks for confirmation and states the scope', async ({ page }) => {
    await signInAsEngineer(page);
    await page.goto(`${ADMIN}/admin/engineering/testing`);
    await expect(page.getByTestId('run-tests')).toBeEnabled();

    await page.getByTestId('test-type').selectOption('UNIT');
    await page.getByTestId('module-selector').getByText('validation', { exact: true }).click();
    await page.getByTestId('run-tests').click();

    // The confirmation names the exact scope, so nobody runs the wrong thing.
    await expect(page.getByText(/UNIT · validation · local/)).toBeVisible();
    await expect(page.getByText(/may take several minutes/)).toBeVisible();

    const accepted = page.waitForResponse(r =>
      r.url().includes('/engineering/testing/runs') && r.request().method() === 'POST');
    await page.getByTestId('confirm-run').click();

    const response = await accepted;
    expect(response.status(), 'the run endpoint answers 202: the work is not finished yet')
      .toBe(202);
    const body = await response.json();
    expect(body.data.runNumber).toMatch(/^RUN-\d{8}-\d{4}$/);
    expect(body.data.status).toBe('QUEUED');

    // It settles on a real terminal state, with real counts.
    await expect(page.getByTestId('run-status')).toHaveText(/COMPLETED|PARTIAL|FAILED/, { timeout: 180000 });
    await expect(page.getByTestId('count-total')).not.toHaveText('0');
    await expect(page.getByTestId('run-history')).toContainText(body.data.runNumber);
    await expect(page.getByTestId('run-history')).toContainText('playwright-qa');
  });

  test('TEST-003 the run scope is an allowlist, not free text', async ({ page }) => {
    const { accessToken } = await signInAsEngineer(page);
    const headers = { Authorization: `Bearer ${accessToken}` };

    // A shell fragment in a module name must be refused by name, not escaped and
    // passed along. This is the assertion that keeps the button from being a
    // remote shell.
    const injected = await page.request.post(`${API}/engineering/testing/runs`, {
      headers, failOnStatusCode: false,
      data: { testType: 'UNIT', modules: ['gst; rm -rf /'], environment: 'local' },
    });
    expect(injected.status()).toBe(400);
    expect(await injected.text()).toContain('Unknown module');

    // Production is not an approved target for a test run.
    const production = await page.request.post(`${API}/engineering/testing/runs`, {
      headers, failOnStatusCode: false,
      data: { testType: 'UNIT', modules: [], environment: 'production' },
    });
    expect(production.status()).toBe(400);

    // And an unknown test type cannot smuggle anything through either.
    const badType = await page.request.post(`${API}/engineering/testing/runs`, {
      headers, failOnStatusCode: false,
      data: { testType: 'ARBITRARY', modules: [], environment: 'local' },
    });
    expect(badType.status()).toBe(400);
  });

  test('TEST-004 the testing API is not reachable without authorization', async ({ page }) => {
    const anonymousRead = await page.request.get(`${API}/engineering/testing/dashboard`,
      { failOnStatusCode: false });
    expect(anonymousRead.status(), 'testing must not be readable anonymously').toBe(401);

    const anonymousRun = await page.request.post(`${API}/engineering/testing/runs`,
      { data: {}, failOnStatusCode: false });
    expect(anonymousRun.status()).toBe(401);

    // A reader may see results but must not be able to start a run.
    const { accessToken } = await signInAsEngineer(page, 'ENGINEERING_ANALYST');
    const forbidden = await page.request.post(`${API}/engineering/testing/runs`, {
      headers: { Authorization: `Bearer ${accessToken}` }, failOnStatusCode: false,
      data: { testType: 'UNIT', modules: [], environment: 'local' },
    });
    expect(forbidden.status(), 'starting a run needs ENGINEERING_ANOMALY_RUN').toBe(403);
  });

  test('TEST-005 the defect register enforces its lifecycle', async ({ page }) => {
    const { accessToken } = await signInAsEngineer(page);
    const headers = { Authorization: `Bearer ${accessToken}` };

    const list = await page.request.get(`${API}/engineering/testing/defects?openOnly=false`, { headers });
    const defects = (await list.json()).data;
    test.skip(defects.length === 0, 'no defects recorded yet');

    const open = defects.find(d => d.status !== 'CLOSED');
    test.skip(!open, 'no open defect to exercise');

    // Closing without a verified retest is refused — this is the gate that stops
    // a defect closing because somebody changed some code.
    const premature = await page.request.post(
      `${API}/engineering/testing/defects/${open.defectNumber}/close`,
      { headers, failOnStatusCode: false });
    expect(premature.status()).toBe(400);
    expect(await premature.text()).toContain('VERIFIED');

    // Verifying against a run that never executed the test is refused too.
    const runs = await page.request.get(`${API}/engineering/testing/runs?limit=20`, { headers });
    const unitRun = (await runs.json()).data.find(r => r.testType === 'UNIT' && r.total > 0);
    if (unitRun) {
      const wrongRun = await page.request.post(
        `${API}/engineering/testing/defects/${open.defectNumber}/verify`,
        { headers, failOnStatusCode: false, data: { runNumber: unitRun.runNumber } });
      expect([400, 409]).toContain(wrongRun.status());
    }
  });

  test('TEST-006 evidence is stored with credentials redacted', async ({ page }) => {
    const { accessToken } = await signInAsEngineer(page);
    const headers = { Authorization: `Bearer ${accessToken}` };

    const list = await page.request.get(`${API}/engineering/testing/defects?openOnly=false`, { headers });
    const defects = (await list.json()).data;
    test.skip(defects.length === 0, 'no defects recorded yet');

    const target = defects[0].defectNumber;
    await page.request.post(`${API}/engineering/testing/defects/${target}/annotate`, {
      headers,
      data: {
        evidenceCurl: "curl -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.fake.signature' http://localhost:8081/x",
        evidenceRequest: '{"password": "hunter2-not-real"}',
      },
    });

    const detail = await page.request.get(`${API}/engineering/testing/defects/${target}`, { headers });
    const d = (await detail.json()).data;

    // A defect record is read and exported by people; it must not carry a
    // working credential.
    expect(d.evidenceCurl).toContain('<REDACTED>');
    expect(d.evidenceCurl).not.toContain('eyJhbGciOiJIUzI1NiJ9.fake.signature');
    expect(d.evidenceRequest).toContain('<REDACTED>');
    expect(d.evidenceRequest).not.toContain('hunter2-not-real');
  });

  test('TEST-007 coverage is reported from an enumerated inventory', async ({ page }) => {
    const { accessToken } = await signInAsEngineer(page);
    const headers = { Authorization: `Bearer ${accessToken}` };

    const res = await page.request.get(`${API}/engineering/testing/coverage`, { headers });
    expect(res.ok()).toBeTruthy();
    const { byKind } = (await res.json()).data;

    const kinds = byKind.map(k => k.key);
    for (const expected of ['UI_ELEMENT', 'API_ENDPOINT', 'ROUTE', 'ENTITY']) {
      expect(kinds, `the inventory must enumerate ${expected}`).toContain(expected);
    }

    // The denominator must be real, and the numerator must never exceed it —
    // a coverage figure over an unenumerated population is not a measurement.
    for (const k of byKind) {
      expect(k.total).toBeGreaterThan(0);
      expect(k.passed + k.failed + k.blocked + k.untested).toBe(k.total);
      expect(k.coveragePercent).toBeLessThanOrEqual(100);
    }
  });
});
