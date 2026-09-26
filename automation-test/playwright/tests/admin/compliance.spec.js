// FIN-006 — a compliance section that depends on a backend listing endpoint loads.
const { test, expect } = require('../../fixtures');

test('FIN-006 the debit notes section loads without a server error @regression @gst', async ({ page }) => {
  const list = page.waitForResponse(r => r.url().includes('/gst/compliance/debit-notes') && r.request().method() === 'GET');
  await page.goto('/admin/gst-compliance?section=debitNotes');
  expect((await list).status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Debit Notes' })).toBeVisible();
  await expect(page.getByText('COULD NOT LOAD')).toHaveCount(0);
});
