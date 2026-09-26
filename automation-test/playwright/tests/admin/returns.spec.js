// RET-003 — returns metrics render from the API once signed in to the GST module.
const { test, expect } = require('../../fixtures');
const { ReturnsPage } = require('../../pages/admin/ReturnsPage');

test('RET-003 the returns screen shows figures, not dashes @regression', async ({ page }) => {
  const returns = new ReturnsPage(page);
  const stats = page.waitForResponse(r => r.url().includes('/returns/stats'));
  await returns.goto();
  expect((await stats).status(), 'stats must be authorised').toBe(200);
  await expect(returns.metric('Total Orders')).toHaveText(/^\d+$/);
  await expect(returns.metric('Total Returns')).toHaveText(/^\d+$/);
  await expect(returns.metric('Return Rate')).toHaveText(/%$/);
});
