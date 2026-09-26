// Order status changes are decided by the backend, not by which buttons the
// admin screen happens to render.
//
// Until P0-5A /sales-order/** sat on the permissive default chain and the
// service wrote whatever status string it was handed, so anyone who could reach
// the server could mark an order delivered, cancel it, or walk an order back out
// of a cancellation. The screen was the only thing enforcing the sequence.
//
// Tagged @mutates: the authorised test advances a real order it creates itself
// and removes afterwards.
const { test, expect } = require('../../fixtures');
const db = require('../../fixtures/db');
const env = require('../../utils/env');
const { randomUUID } = require('crypto');

/** A dev-issued token carrying the named GST role. Local profile only. */
async function tokenFor(ctx, role) {
  const res = await ctx.post(`${env.API_URL}/dev-auth/token`, {
    data: { username: `pw-${role.toLowerCase()}`, roles: [role] },
  });
  expect(res.ok(), `dev-auth must issue a ${role} token: ${await res.text()}`).toBeTruthy();
  return (await res.json()).data.accessToken;
}

/** An order sitting at ORDER_PLACED, with one unit of its own product. */
async function seedOrder() {
  const productUuid = randomUUID();
  await db.q(
    `INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                    initial_stock, hsn_code, archive, created)
     VALUES ($1::uuid, 'Automation Lifecycle Product', $2, 100000, 100000, 'INR', 5, '6211', false, now())`,
    [productUuid, `AUTO-PWL-${productUuid.slice(0, 8)}`]);
  const { rows: prod } = await db.q('SELECT id FROM inventory_product WHERE uuid = $1::uuid', [productUuid]);

  const orderCode = `VO-PWL-${randomUUID().slice(0, 8).toUpperCase()}`;
  await db.q(
    `INSERT INTO customer_order (uuid, order_code, customer_id, customer_email, delivery_location,
                                 currency, total_value, status, order_placed_at, active, archive)
     VALUES (gen_random_uuid(), $1, $2, $3, '12 MG Road, Bengaluru', 'INR', 100000, 'ORDER_PLACED',
             timezone('UTC', now()), true, false)`,
    [orderCode, randomUUID(), `pwl-${randomUUID().slice(0, 8)}@automation.veloria.test`]);
  const { rows: ord } = await db.q('SELECT id FROM customer_order WHERE order_code = $1', [orderCode]);

  await db.q(
    `INSERT INTO customer_order_item (uuid, customer_order_id, product_uuid, quantity, archive)
     VALUES (gen_random_uuid(), $1, $2::uuid, 1, false)`, [ord[0].id, productUuid]);

  return { orderCode, orderId: ord[0].id, productId: prod[0].id };
}

async function removeOrder({ orderCode, orderId, productId }) {
  await db.q('DELETE FROM customer_order_item WHERE customer_order_id = $1', [orderId]);
  await db.q('DELETE FROM customer_order WHERE order_code = $1', [orderCode]);
  await db.q('DELETE FROM inventory_product WHERE id = $1', [productId]);
}

async function statusOf(orderCode) {
  const { rows } = await db.q('SELECT status FROM customer_order WHERE order_code = $1', [orderCode]);
  return rows[0]?.status;
}

test.describe('Order lifecycle is enforced by the backend @regression @security @admin @mutates', () => {

  test('an unauthenticated status change is refused and the order is untouched', async ({ playwright }) => {
    const ctx = await playwright.request.newContext();
    const order = await seedOrder();
    try {
      const res = await ctx.fetch(
        `${env.API_URL}/sales-order/order/${order.orderCode}/status`,
        { method: 'PATCH', data: { status: 'DELIVERED' } });

      expect([401, 403], `an anonymous status change must be refused, got ${res.status()}`)
        .toContain(res.status());
      expect(await statusOf(order.orderCode),
        'a refused request must not move the order').toBe('ORDER_PLACED');
    } finally {
      await removeOrder(order);
      await ctx.dispose();
    }
  });

  test('an unauthenticated cancellation is refused and records no reason', async ({ playwright }) => {
    const ctx = await playwright.request.newContext();
    const order = await seedOrder();
    try {
      const res = await ctx.post(
        `${env.API_URL}/sales-order/order/${order.orderCode}/cancel`,
        { data: { reason: 'not mine to cancel' } });

      expect([401, 403]).toContain(res.status());
      expect(await statusOf(order.orderCode)).toBe('ORDER_PLACED');
      const { rows } = await db.q(
        'SELECT cancel_reason FROM customer_order WHERE order_code = $1', [order.orderCode]);
      expect(rows[0].cancel_reason, 'no reason may have been written').toBeNull();
    } finally {
      await removeOrder(order);
      await ctx.dispose();
    }
  });

  test('order figures are not readable without credentials', async ({ playwright }) => {
    const ctx = await playwright.request.newContext();
    try {
      const res = await ctx.get(`${env.API_URL}/sales-order/stats`);
      expect([401, 403], `revenue must not be public, got ${res.status()}`).toContain(res.status());
    } finally {
      await ctx.dispose();
    }
  });

  test('a viewer may read order figures but may not move an order', async ({ playwright }) => {
    const ctx = await playwright.request.newContext();
    const order = await seedOrder();
    try {
      const viewer = await tokenFor(ctx, 'GST_VIEWER');
      const auth = { Authorization: `Bearer ${viewer}` };

      const read = await ctx.get(`${env.API_URL}/sales-order/stats`, { headers: auth });
      expect(read.ok(), 'a viewer should be able to read').toBeTruthy();

      const write = await ctx.fetch(
        `${env.API_URL}/sales-order/order/${order.orderCode}/status`,
        { method: 'PATCH', headers: auth, data: { status: 'PACKED' } });
      expect(write.status(), 'reading does not imply moving').toBe(403);
      expect(await statusOf(order.orderCode)).toBe('ORDER_PLACED');
    } finally {
      await removeOrder(order);
      await ctx.dispose();
    }
  });

  test('an administrator advances an order, and cannot skip a step or revive a cancellation', async ({ playwright }) => {
    const ctx = await playwright.request.newContext();
    const order = await seedOrder();
    try {
      const admin = await tokenFor(ctx, 'GST_ADMIN');
      const auth = { Authorization: `Bearer ${admin}` };
      const move = (status) => ctx.fetch(
        `${env.API_URL}/sales-order/order/${order.orderCode}/status`,
        { method: 'PATCH', headers: auth, data: { status } });

      // Skipping ahead is refused even with full authority — the screen never
      // offers this, and now neither does the API.
      const skipped = await move('DELIVERED');
      expect(skipped.status(), 'ORDER_PLACED → DELIVERED must be refused').toBe(400);
      expect((await skipped.json()).message).toMatch(/cannot be moved to DELIVERED/i);
      expect(await statusOf(order.orderCode)).toBe('ORDER_PLACED');

      // An unknown status is refused rather than stored.
      const nonsense = await move('SHIPPED_TO_MARS');
      expect(nonsense.status()).toBe(400);
      expect((await nonsense.json()).message).toMatch(/unknown order status/i);
      expect(await statusOf(order.orderCode)).toBe('ORDER_PLACED');

      // The real sequence works.
      expect((await move('PACKED')).ok()).toBeTruthy();
      expect(await statusOf(order.orderCode)).toBe('PACKED');

      // Repeating it is safe.
      expect((await move('PACKED')).ok()).toBeTruthy();
      expect(await statusOf(order.orderCode)).toBe('PACKED');

      // Cancel, then try to walk back out of it.
      const cancelled = await ctx.post(
        `${env.API_URL}/sales-order/order/${order.orderCode}/cancel`,
        { headers: auth, data: { reason: 'automation' } });
      expect(cancelled.ok()).toBeTruthy();
      expect(await statusOf(order.orderCode)).toBe('CANCELLED');

      const revived = await move('PACKED');
      expect(revived.status(), 'a cancellation must not be bypassed').toBe(400);
      expect((await revived.json()).message).toMatch(/final/i);
      expect(await statusOf(order.orderCode)).toBe('CANCELLED');
    } finally {
      await removeOrder(order);
      await ctx.dispose();
    }
  });

  test('the sales screen sends a bearer token when it changes a status', async ({ page, playwright }) => {
    // Seeds its own ORDER_PLACED row rather than depending on whatever the
    // environment happens to contain, so this never silently skips.
    const order = await seedOrder();
    const ctx = await playwright.request.newContext();
    try {
      // The Sales screen now needs an authorised token even to list orders,
      // so plant one the way the admin app's own GST sign-in does.
      const admin = await tokenFor(ctx, 'GST_ADMIN');
      await page.addInitScript((token) => {
        sessionStorage.setItem('gstAccessToken', token);
        sessionStorage.setItem('gstAccessTokenExpiry',
          new Date(Date.now() + 3600_000).toISOString());
        sessionStorage.setItem('gstRole', 'GST_ADMIN');
      }, admin);

      // Intercepted before it reaches the server: this test is about what the
      // screen sends, not about moving a real order.
      const seen = { called: false, authorization: undefined };
      await page.route(u => /\/sales-order\/order\/.*\/status/.test(u.href), async (route, request) => {
        seen.called = true;
        seen.authorization = request.headers()['authorization'];
        await route.fulfill({
          status: 200, contentType: 'application/json',
          body: JSON.stringify({ code: 'OK', message: 'stubbed by test', data: null }),
        });
      });

      await page.goto(`/admin/sales?search=${order.orderCode}`);
      const packed = page.getByRole('button', { name: /^packed$/i }).first();
      await expect(packed, 'the seeded order should offer its next step').toBeVisible({ timeout: 15000 });
      await packed.click();

      await expect.poll(() => seen.called, { timeout: 10000 }).toBe(true);
      expect(seen.authorization, 'the screen must send a bearer token').toMatch(/^Bearer .+/);
    } finally {
      await removeOrder(order);
      await ctx.dispose();
    }
  });
});
