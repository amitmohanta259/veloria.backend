// A retried checkout must not become a second order.
//
// What the browser contributes to this protection is the reference: generated
// once per attempt to pay, resent unchanged on a retry, discarded once an order
// exists. These tests check that the real page does exactly that, and that
// replaying the request the page sent — which is what a retry after a lost
// response is — returns the original order rather than creating another.
//
// True concurrency is proved against PostgreSQL in OrderIdempotencyPostgresTest.
// A browser cannot demonstrate row contention and is not used to claim it here.
//
// Tagged @mutates: real orders are placed. The buyer fixture removes them.
const { test, expect } = require('../../../fixtures');
const db = require('../../../fixtures/db');
const addresses = require('../../../test-data/addresses');

/** A product with exactly this many units, removed when the test finishes. */
async function seedProduct(stock) {
  const { randomUUID } = require('crypto');
  const uuid = randomUUID();
  await db.q(
    `INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                    initial_stock, hsn_code, archive, created)
     VALUES ($1::uuid, 'Automation Idempotency Stock', $2, 100000, 100000, 'INR', $3, '6211', false, now())`,
    [uuid, `AUTO-PWI-${uuid.slice(0, 8)}`, stock]);
  const { rows } = await db.q('SELECT id FROM inventory_product WHERE uuid = $1::uuid', [uuid]);
  await db.q(
    `INSERT INTO inventory_product_size_stock (product_id, size, initial_stock, archive)
     VALUES ($1, 'M', $2, false)`, [rows[0].id, stock]);
  return { uuid, id: rows[0].id };
}

async function removeProduct(id) {
  await db.q('DELETE FROM inventory_product_size_stock WHERE product_id = $1', [id]);
  await db.q('DELETE FROM inventory_product WHERE id = $1', [id]);
}

async function ordersFor(reference) {
  const { rows } = await db.q(
    'SELECT order_code FROM customer_order WHERE client_order_reference = $1', [reference]);
  return rows.map(r => r.order_code);
}

test.describe('A retried checkout stays one order @regression @mutates @idempotency', () => {


  test('replaying the request the page sent returns the same order, and sells one unit', async ({ page, api, buyer, session, signIn }) => {
    const product = await seedProduct(5);
    try {
      await db.seedDefaultAddress(buyer, addresses.karnataka.stateCode, addresses.karnataka.pincode);
      await signIn(page, session, buyer);

      const auth = { Authorization: `Bearer ${session}` };
      const reference = `pw-chk-${require('crypto').randomUUID()}`;
      const body = {
        deliveryLocation: addresses.karnataka.line,
        currency: 'INR',
        clientOrderReference: reference,
        items: [{ productUuid: product.uuid, quantity: 1 }],
      };

      const first = await api.post('/client/order/place', { headers: auth, data: body });
      expect(first.ok(), `first checkout should succeed: ${await first.text()}`).toBeTruthy();
      const orderCode = (await first.json()).data.orderCode;

      // The retry: byte-for-byte the same request, as a browser resend would be.
      const retry = await api.post('/client/order/place', { headers: auth, data: body });
      expect(retry.ok(), `the retry should succeed: ${await retry.text()}`).toBeTruthy();
      expect((await retry.json()).data.orderCode,
        'the retry must be given the order the first attempt created').toBe(orderCode);

      expect(await ordersFor(reference), 'one reference, one order').toEqual([orderCode]);

      // And the shopper sees one order, not two.
      await page.goto('/order-history');
      await expect(page.getByText(orderCode)).toHaveCount(1);
    } finally {
      await removeProduct(product.id);
    }
  });

  // A real catalogue product, not a seeded one: this test goes through the bag
  // and the payment screen, which render only what the storefront can show.
  test('the checkout page sends a reference, and starts a new one for the next purchase', async ({ page, api, buyer, session, signIn, aProduct }) => {
    {
      await db.seedDefaultAddress(buyer, addresses.karnataka.stateCode, addresses.karnataka.pincode);
      await signIn(page, session, buyer);

      // Put the product in the bag through the API, then pay through the UI.
      const auth = { Authorization: `Bearer ${session}` };
      const added = await api.post('/client/bag/add', {
        headers: auth,
        data: { productUuid: aProduct.uuid, quantity: 1 },
      });
      expect(added.ok(), `seeding the bag should succeed: ${await added.text()}`).toBeTruthy();

      await page.goto('/payment');
      // The page picks the default address once the address list arrives; waiting
      // for it is what makes the checkout ready, not merely rendered.
      await expect(page.getByText('12 Automation Lane').first()).toBeVisible();
      await page.getByRole('button', { name: /Cash on Delivery/i }).click();
      const placeOrder = page.getByRole('button', { name: /^place order$/i });
      await expect(placeOrder).toBeEnabled();

      // Capture what the page actually sends.
      const placed = page.waitForRequest(r =>
        r.url().includes('/client/order/place') && r.method() === 'POST');
      const response = page.waitForResponse(r =>
        r.url().includes('/client/order/place') && r.request().method() === 'POST');
      await placeOrder.click();

      const sent = JSON.parse((await placed).postData());
      expect(sent.clientOrderReference,
        'the page must identify the checkout attempt so a retry can be recognised').toBeTruthy();
      expect(typeof sent.clientOrderReference).toBe('string');
      expect(sent.clientOrderReference.length,
        'the column holds at most 64 characters').toBeLessThanOrEqual(64);

      const res = await response;
      expect(res.ok(), `the order should be placed: ${await res.text()}`).toBeTruthy();
      expect(await ordersFor(sent.clientOrderReference)).toHaveLength(1);

      // Spent: the reference is cleared once an order exists, so the next
      // purchase cannot be mistaken for a retry of this one.
      await expect(page).toHaveURL(/order-confirmation/);
      const stored = await page.evaluate(() => sessionStorage.getItem('veloria.checkoutReference'));
      expect(stored, 'a completed checkout must not leave its reference behind').toBeNull();
    }
  });

  test('a different bag under the same reference is refused, and the first order stands', async ({ api, buyer, session, page, signIn }) => {
    const product = await seedProduct(5);
    try {
      await db.seedDefaultAddress(buyer, addresses.karnataka.stateCode, addresses.karnataka.pincode);
      await signIn(page, session, buyer);

      const auth = { Authorization: `Bearer ${session}` };
      const reference = `pw-chk-${require('crypto').randomUUID()}`;
      const base = {
        deliveryLocation: addresses.karnataka.line,
        currency: 'INR',
        clientOrderReference: reference,
      };

      const first = await api.post('/client/order/place', {
        headers: auth,
        data: { ...base, items: [{ productUuid: product.uuid, quantity: 1 }] },
      });
      expect(first.ok()).toBeTruthy();
      const orderCode = (await first.json()).data.orderCode;

      const changed = await api.post('/client/order/place', {
        headers: auth,
        data: { ...base, items: [{ productUuid: product.uuid, quantity: 3 }] },
      });
      expect(changed.status(), 'the same reference cannot stand for a different order').toBe(400);
      const message = (await changed.json()).message ?? '';
      expect(message).toMatch(/different order/i);
      // Nothing internal leaks into what the shopper is shown.
      expect(message).not.toMatch(/Exception|SQL|constraint|org\.|jakarta\./);

      expect(await ordersFor(reference), 'the first order is untouched').toEqual([orderCode]);
    } finally {
      await removeProduct(product.id);
    }
  });
});
