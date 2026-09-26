// GST-008, GST-009 — the tax engine's admin screen, signed in to the GST module automatically.
const { test, expect } = require('../../fixtures');
const { GstManagementPage } = require('../../pages/admin/GstManagementPage');
const gst = require('../../test-data/gst');

test.describe('GST Management @regression @gst', () => {
  test('GST-008 the rules table lists the configured rules @smoke', async ({ page }) => {
    const gstPage = new GstManagementPage(page);
    const rules = page.waitForResponse(r => r.url().includes('/gst/rules') && r.request().method() === 'GET');
    await gstPage.goto();
    expect((await rules).status(), 'rules must load (needs the GST sign-in)').toBe(200);
    await expect(gstPage.rulesTable.getByRole('cell', { name: gst.knownRule.hsn, exact: true }).first()).toBeVisible();
  });

  test('GST-009 the calculator charges IGST on an inter-state supply', async ({ page }) => {
    const gstPage = new GstManagementPage(page);
    await gstPage.goto();
    await gstPage.openCalculator();
    await gstPage.fillCalculator(gst.interStateApparel);
    const calc = page.waitForResponse(r => r.url().includes('/gst/calculate') && r.request().method() === 'POST');
    await gstPage.calculate.click();
    expect((await calc).ok()).toBeTruthy();
    await expect(page.getByText('Inter-State (IGST)')).toBeVisible();
    await expect(page.getByText(`IGST (${gst.interStateApparel.igstPercent}%)`)).toBeVisible();
    // ₹180 is shown twice (IGST and Total Tax, which are equal here); the grand total is unique.
    await expect(page.getByText(`₹${gst.interStateApparel.igstRupees}`, { exact: true }).first()).toBeVisible();
    await expect(page.getByText(`₹${gst.interStateApparel.totalRupees}`, { exact: true })).toBeVisible();
  });
});
