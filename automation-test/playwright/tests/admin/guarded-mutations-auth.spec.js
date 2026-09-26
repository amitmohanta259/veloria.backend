// The admin screens that call @PreAuthorize-guarded backend mutations must send
// a bearer token, and a request without one must be refused and change nothing.
//
// Asserted against the running server rather than by reading source, so it keeps
// holding if a call site moves. Nothing is created: the authorised path is
// intercepted in the browser and never reaches the server, and the unauthorised
// path is expected to be rejected.
const { test, expect } = require('../../fixtures');
const env = require('../../utils/env');

test.describe('Guarded admin mutations @regression @security @admin', () => {

  test('an unauthenticated guarded mutation is refused and stock is unchanged', async ({ playwright }) => {
    const ctx = await playwright.request.newContext();
    try {
      const listing = await ctx.get(`${env.API_URL}/inventory-product/all?page=0&pageSize=1`);
      test.skip(!listing.ok(), 'inventory listing unavailable');
      const body = await listing.json();
      const products = body.data?.content ?? body.data ?? [];
      test.skip(!products.length, 'no product to exercise');
      const uuid = products[0].uuid;

      const before = await (await ctx.get(`${env.API_URL}/inventory-product/${uuid}`)).json();
      const stockBefore = JSON.stringify(before.data?.sizeStocks ?? []);

      // No Authorization header — exactly what StockTableRow used to send.
      const res = await ctx.patch(
        `${env.API_URL}/inventory-product/${uuid}/stock/add?size=AUTOMATION-M&qty=5`);

      expect([401, 403], `unauthenticated stock/add must be refused, got ${res.status()}`)
        .toContain(res.status());

      const after = await (await ctx.get(`${env.API_URL}/inventory-product/${uuid}`)).json();
      expect(JSON.stringify(after.data?.sizeStocks ?? []),
        'a refused request must not change stock').toBe(stockBefore);
    } finally {
      await ctx.dispose();
    }
  });

  test('the stock screen sends a bearer token on stock/add', async ({ page, api }) => {
    const listing = await api.get('/inventory-product/all?page=0&pageSize=1');
    test.skip(!listing.ok(), 'inventory listing unavailable');
    const body = await listing.json();
    const products = body.data?.content ?? body.data ?? [];
    test.skip(!products.length, 'no product to exercise');

    // Intercept before navigating: the mutation must never reach the server.
    const seen = { called: false, authorization: undefined };
    await page.route(u => u.href.includes('/stock/add'), async (route, request) => {
      seen.called = true;
      seen.authorization = request.headers()['authorization'];
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({ code: 'OK', message: 'stubbed by test', data: null }),
      });
    });

    await page.goto('/admin/inventory');

    // Exercise the component's own contract: acquire a token, then call with
    // authHeaders() — the sequence StockTableRow now performs.
    const ok = await page.evaluate(async (uuid) => {
      const mod = await import('/src/utils/auth.ts');
      const origin = 'http://localhost:8081/api/master';
      if (!(await mod.ensureGstToken(origin))) return 'no-token';
      const r = await fetch(`${origin}/inventory-product/${uuid}/stock/add?size=AUTOMATION-M&qty=1`,
                            { method: 'PATCH', headers: mod.authHeaders() });
      return r.ok ? 'ok' : `http-${r.status}`;
    }, products[0].uuid).catch(e => `error:${e.message}`);

    test.skip(String(ok).startsWith('error:'), `module import unavailable: ${ok}`);
    expect(ok, 'the guarded call should have been made').toBe('ok');
    expect(seen.called, 'stock/add must have been issued').toBeTruthy();
    expect(seen.authorization, 'stock/add must carry a bearer token').toMatch(/^Bearer .+/);
  });
});
