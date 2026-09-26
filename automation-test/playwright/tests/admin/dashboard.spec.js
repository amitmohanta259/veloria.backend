// FIN-005 — the admin dashboard shows live figures, not placeholders.
const { test, expect } = require('../../fixtures');
const { DashboardPage } = require('../../pages/admin/DashboardPage');

test('FIN-005 the dashboard renders live figures @smoke @regression', async ({ page }) => {
  const dashboard = new DashboardPage(page);
  const summary = page.waitForResponse(r => r.url().includes('/dashboard/summary'));
  await dashboard.goto();
  expect((await summary).ok()).toBeTruthy();
  await expect(dashboard.heading).toBeVisible();
  await expect(dashboard.metric('Total Orders')).toHaveText(/^\d+$/);
  await expect(dashboard.metric('Active Orders')).toHaveText(/^\d+$/);
});
