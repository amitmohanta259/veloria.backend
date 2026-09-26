// AUTH-013 … AUTH-017 — what the shopper sees when a session is gone, lapsed, or real.
const { test, expect } = require('../../../fixtures');
const db = require('../../../fixtures/db');
const env = require('../../../utils/env');
const { NavBar } = require('../../../pages/client/NavBar');
const { ProductPage } = require('../../../pages/client/ProductPage');
const { LoginPage } = require('../../../pages/client/LoginPage');

const STALE = '00000000-dead-dead-dead-000000000000';

test.describe('Expired session @regression @security', () => {
  test('AUTH-013 browsing continues and the header offers Sign in @smoke', async ({ page, signIn, buyer }) => {
    await signIn(page, STALE, buyer);
    await page.goto('/');

    const nav = new NavBar(page);
    await expect(nav.signInButton).toBeVisible();
    await expect(nav.accountButton).toHaveCount(0);
    await expect(page).toHaveURL(/\/$/);
    await expect.poll(() => page.evaluate(() => localStorage.getItem('clientToken'))).toBeNull();
  });

  test('AUTH-014 order history sends the shopper to sign in', async ({ page, signIn, buyer }) => {
    await signIn(page, STALE, buyer);
    await page.goto('/order-history');
    await expect(page).toHaveURL(/\/login$/);
  });

  test('AUTH-015 acting on a product sends the shopper to sign in', async ({ page, signIn, buyer, aProduct }) => {
    await signIn(page, STALE, buyer);
    const product = new ProductPage(page);
    await product.goto(aProduct.uuid);
    await expect(product.buyNow).toBeVisible();
    await product.buyNowClick();
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe('Token rotation @regression @security', () => {
  test('AUTH-016 a lapsed token is refreshed on load and the shopper stays signed in @smoke @critical', async ({ page, signIn, buyer }) => {
    // Access lapsed two hours ago; the login itself is alive for a week.
    const lapsed = await db.seedSession(buyer, -2 * 3600, 7 * 24 * 3600);
    await signIn(page, lapsed, buyer);

    const refresh = page.waitForResponse(r => r.url().includes('/client/session/refresh') && r.request().method() === 'POST');
    await page.goto('/');
    const res = await refresh;
    expect(res.ok(), 'the refresh must succeed').toBeTruthy();

    const nav = new NavBar(page);
    await expect(nav.accountButton).toBeVisible();
    await expect(nav.signInButton).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => localStorage.getItem('clientToken'))).not.toBe(lapsed);
  });
});

test.describe('Real sign-in @regression', () => {
  test.skip(!env.TEST_USERNAME || !env.TEST_PASSWORD, 'TEST_USERNAME / TEST_PASSWORD not set — see README');

  test('AUTH-017 signing in with the password form lands on the home page', async ({ page }) => {
    const login = new LoginPage(page);
    await login.goto();
    await login.login(env.TEST_USERNAME, env.TEST_PASSWORD);
    await expect(page).toHaveURL(/\/$/);
    await expect(new NavBar(page).accountButton).toBeVisible();
  });
});
