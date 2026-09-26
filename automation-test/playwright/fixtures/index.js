const base = require('@playwright/test');
const db = require('./db');
const env = require('../utils/env');

/**
 * Fixtures every spec composes from.
 *
 *  buyer        a seeded shopper row, removed after the test (orders included)
 *  session      a live token for that buyer
 *  signIn(page, token)   plants a token so the app boots signed in — the
 *               same storage the real login writes, nothing more
 *  api          a request context on the backend, for setup and for checking
 *               what the UI did
 *  aProduct     a real catalogue product to browse to
 */
const test = base.test.extend({
  buyer: async ({}, use) => {
    const buyer = await db.seedBuyer();
    await use(buyer);
    await db.removeBuyer(buyer);
  },

  session: async ({ buyer }, use) => {
    await use(await db.seedSession(buyer));
  },

  signIn: async ({}, use) => {
    await use(async (page, token, buyer) => {
      await page.addInitScript(({ token, buyer }) => {
        localStorage.setItem('clientToken', token);
        localStorage.setItem('clientUser', JSON.stringify({
          userId: buyer.userUuid, name: buyer.name, email: buyer.email, phone: buyer.phone,
        }));
      }, { token, buyer });
    });
  },

  api: async ({ playwright }, use) => {
    // No baseURL on purpose: request contexts resolve a leading-slash path
    // against the origin, which would silently drop the /api/master prefix.
    const ctx = await playwright.request.newContext();
    const at = (path) => env.API_URL + path;
    await use({
      get: (path, opts) => ctx.get(at(path), opts),
      post: (path, opts) => ctx.post(at(path), opts),
    });
    await ctx.dispose();
  },

  aProduct: async ({ api }, use) => {
    // Deliberately not /products/new-in: that endpoint only returns products
    // created in the last 30 days, so a fixture built on it silently empties
    // once the seed data ages and every test depending on it fails for a
    // reason that has nothing to do with the code under test.
    const res = await api.get('/client/products/all');
    base.expect(res.ok(), 'catalogue must be reachable').toBeTruthy();
    const list = (await res.json()).data ?? [];
    base.expect(list.length, 'catalogue must not be empty').toBeGreaterThan(0);
    await use(list[0]);
  },
});

module.exports = { test, expect: base.expect };
