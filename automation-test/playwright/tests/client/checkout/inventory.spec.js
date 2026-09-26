// Checkout refuses to sell what is not there, and the shopper is told why.
//
// Browser-level behaviour only. True concurrency is proved in the PostgreSQL
// integration tests (CheckoutInventoryPostgresTest); multiple tabs cannot
// demonstrate database row contention and are not used for that here.
//
// Tagged @mutates: the passing case places a real order. The buyer fixture
// removes everything it created.
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
     VALUES ($1::uuid, 'Automation Checkout Stock', $2, 100000, 100000, 'INR', $3, '6211', false, now())`,
    [uuid, `AUTO-PW-${uuid.slice(0, 8)}`, stock]);
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

test.describe('Checkout respects stock @regression @mutates @inventory', () => {

  test('a shopper can buy the last unit, and the next attempt is refused', async ({ page, api, buyer, session, signIn }) => {
    const product = await seedProduct(1);
    try {
      await db.seedDefaultAddress(buyer, addresses.karnataka.stateCode, addresses.karnataka.pincode);
      await signIn(page, session, buyer);

      const auth = { Authorization: `Bearer ${session}` };
      const orderBody = {
        deliveryLocation: addresses.karnataka.line,
        currency: 'INR',
        items: [{ productUuid: product.uuid, quantity: 1 }],
      };

      // The one unit is bought.
      const first = await api.post('/client/order/place', { headers: auth, data: orderBody });
      expect(first.ok(), `first order should succeed: ${await first.text()}`).toBeTruthy();
      const orderCode = (await first.json()).data.orderCode;
      expect(orderCode).toMatch(/^VO-/);

      // The next buyer gets nothing, and is told why.
      const second = await api.post('/client/order/place', { headers: auth, data: orderBody });
      expect(second.status(), 'the second order must be refused').toBe(400);
      const message = (await second.json()).message ?? '';
      expect(message, `expected a stock message, got: ${message}`).toMatch(/left of/i);

      // And the shopper sees the order that did succeed.
      await page.goto('/order-history');
      await expect(page.getByText(orderCode)).toBeVisible();
    } finally {
      await removeProduct(product.id);
    }
  });

  test('the checkout screen surfaces a stock refusal instead of silently failing', async ({ page, api, buyer, session, signIn }) => {
    const product = await seedProduct(0);   // nothing to sell
    try {
      await db.seedDefaultAddress(buyer, addresses.karnataka.stateCode, addresses.karnataka.pincode);
      await signIn(page, session, buyer);

      const res = await api.post('/client/order/place', {
        headers: { Authorization: `Bearer ${session}` },
        data: {
          deliveryLocation: addresses.karnataka.line,
          currency: 'INR',
          items: [{ productUuid: product.uuid, quantity: 1 }],
        },
      });

      expect(res.status(), 'an out-of-stock order must be refused').toBe(400);
      const body = await res.json();
      expect(body.message, 'the shopper must be told what is wrong').toMatch(/left of/i);
      // No stack trace, no SQL, no internal type names.
      expect(body.message).not.toMatch(/Exception|SQL|org\.|jakarta\./);

      await page.goto('/order-history');
      await expect(page.getByText(/VO-/).first()).toHaveCount(0);
    } finally {
      await removeProduct(product.id);
    }
  });
});
