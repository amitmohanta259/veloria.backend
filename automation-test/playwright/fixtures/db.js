const { Pool } = require('pg');
const { randomUUID } = require('crypto');
const env = require('../utils/env');

/**
 * Deterministic data for browser tests, seeded straight into the database.
 *
 * A signed-in shopper is a `client_session` row: the app needs nothing else
 * to treat a request as authenticated, so no test holds a password.
 * Everything seeded here is removed by the fixture that created it.
 */
// One pool per worker process. It is never ended explicitly: a worker runs
// many spec files, and ending it in one file's afterAll breaks the next.
// Idle clients release themselves and let the process exit.
const pool = new Pool({
  host: env.DB_HOST, port: env.DB_PORT, database: env.DB_NAME,
  user: env.DB_USER, password: env.DB_PASSWORD,
  max: 4, idleTimeoutMillis: 1000, allowExitOnIdle: true,
});

const q = (sql, params = []) => pool.query(sql, params);

async function seedBuyer() {
  const userUuid = randomUUID();
  const email = `ui-${userUuid.slice(0, 8)}@automation.veloria.test`;
  await q(
    `INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
     VALUES ($1::uuid, 'Automation', 'Buyer', $2, '9000000000', true, false, now())`,
    [userUuid, email],
  );
  return { userUuid, email, name: 'Automation Buyer', phone: '9000000000' };
}

/** accessSeconds / loginSeconds are offsets from now; negative fabricates a lapsed or ended session. */
async function seedSession(buyer, accessSeconds = 600, loginSeconds = 7 * 24 * 3600) {
  const token = `ui-${randomUUID()}`;
  await q(
    `INSERT INTO client_session (token, user_id, name, email, phone, expiry, access_expiry)
     VALUES ($1, $2, $3, $4, $5,
             timezone('UTC', now()) + make_interval(secs => $6),
             timezone('UTC', now()) + make_interval(secs => $7))`,
    [token, buyer.userUuid, buyer.name, buyer.email, buyer.phone, loginSeconds, accessSeconds],
  );
  return token;
}

async function seedDefaultAddress(buyer, stateCode = '29', pincode = '560001') {
  await q(
    `INSERT INTO user_address (uuid, user_id, receiver_name, phone, address, city, state_code, pincode, is_default, active, archive, created_at)
     VALUES ($1::uuid, $2, 'Automation Buyer', '9000000000', '12 Automation Lane', 'Bengaluru', $3, $4, true, true, false, now())`,
    [randomUUID(), buyer.userUuid, stateCode, pincode],
  );
}

/** Everything an order writes, in dependency order. See the module README. */
async function removeOrdersOf(buyer) {
  const { rows } = await q(`SELECT id, order_code FROM customer_order WHERE customer_email = $1`, [buyer.email]);
  for (const { id, order_code } of rows) {
    // Journals BEFORE payments, not after. A collection journal is keyed on the
    // payment attempt and a refund journal on the refund row, so deleting those
    // rows first leaves the subqueries below matching nothing and the journals
    // behind as orphans — which is exactly how 101 stray SALE journals once
    // shifted the historical checksum.
    //
    // Reversals reference the entry they reverse, so they go first of all. An
    // order books a sale when it is created, a COD fee when cash on delivery is
    // chosen, and a reversal when it is cancelled.
    await q(`DELETE FROM journal_entry_line WHERE journal_entry_id IN
               (SELECT r.id FROM journal_entry r WHERE r.reverses_journal_id IN
                 (SELECT id FROM journal_entry
                   WHERE source_type IN ('SALE', 'COD_FEE') AND CAST(source_id AS TEXT) = $1))`, [String(id)]);
    await q(`DELETE FROM journal_entry WHERE reverses_journal_id IN
               (SELECT id FROM journal_entry
                 WHERE source_type IN ('SALE', 'COD_FEE') AND CAST(source_id AS TEXT) = $1)`, [String(id)]);
    await q(`DELETE FROM journal_entry_line WHERE journal_entry_id IN
               (SELECT id FROM journal_entry
                 WHERE (source_type IN ('SALE', 'COD_FEE') AND CAST(source_id AS TEXT) = $1)
                    OR (source_type = 'PAYMENT_COLLECTION' AND source_id IN
                        (SELECT pa.id FROM payment_attempt pa WHERE pa.customer_order_id = $2))
                    OR (source_type = 'REFUND' AND source_id IN
                        (SELECT pr.id FROM payment_refund pr WHERE pr.customer_order_id = $2)))`, [String(id), id]);
    await q(`DELETE FROM journal_entry
               WHERE (source_type IN ('SALE', 'COD_FEE') AND CAST(source_id AS TEXT) = $1)
                  OR (source_type = 'PAYMENT_COLLECTION' AND source_id IN
                      (SELECT pa.id FROM payment_attempt pa WHERE pa.customer_order_id = $2))
                  OR (source_type = 'REFUND' AND source_id IN
                      (SELECT pr.id FROM payment_refund pr WHERE pr.customer_order_id = $2))`, [String(id), id]);
    await q(`DELETE FROM payment_refund WHERE customer_order_id = $1`, [id]);
    await q(`DELETE FROM payment_attempt WHERE customer_order_id = $1`, [id]);
    await q(`DELETE FROM gst_movement_ledger WHERE
               (source_type = 'SALES_INVOICE_ITEM' AND CAST(source_id AS TEXT) IN
                 (SELECT CAST(i.id AS TEXT) FROM sales_invoice_item i JOIN sales_invoice s ON s.id = i.sales_invoice_id WHERE s.order_code = $1))
               OR CAST(source_id AS TEXT) = $1`, [order_code]);
    await q(`DELETE FROM sales_invoice_item WHERE sales_invoice_id IN (SELECT id FROM sales_invoice WHERE order_code = $1)`, [order_code]);
    await q(`DELETE FROM sales_invoice WHERE order_code = $1`, [order_code]);
    await q(`DELETE FROM gst_output_tax WHERE order_code = $1`, [order_code]);
    await q(`DELETE FROM gst_accounting_exception WHERE CAST(source_id AS TEXT) = $1`, [order_code]);
    await q(`DELETE FROM sales_order WHERE order_code = $1`, [order_code]);
    await q(`DELETE FROM customer_order_item WHERE customer_order_id = $1`, [id]);
    await q(`DELETE FROM customer_order WHERE id = $1`, [id]);
  }
  return rows.map(r => r.order_code);
}

async function removeBuyer(buyer) {
  await removeOrdersOf(buyer);
  await q(`DELETE FROM customer_bag WHERE user_id = $1`, [buyer.userUuid]);
  await q(`DELETE FROM customer_favourite WHERE user_id = $1`, [buyer.userUuid]);
  await q(`DELETE FROM user_address WHERE user_id = $1`, [buyer.userUuid]);
  await q(`DELETE FROM client_session WHERE user_id = $1`, [buyer.userUuid]);
  await q(`DELETE FROM users WHERE uuid = $1::uuid`, [buyer.userUuid]);
}

module.exports = { q, seedBuyer, seedSession, seedDefaultAddress, removeOrdersOf, removeBuyer };
