// The checkout screen must show the server's money, not its own.
//
// Until P0-12 the payment screen added a ₹50 literal of its own and rendered the
// COD total as `grandTotal + 50`. P0-14 then made the charge a configured, taxed
// service — so the screen must also handle the charge being unavailable, which is
// the shipped state until an administrator configures a SAC and a GST rule for it.
//
// These cover both states, and neither depends on unapproved tax configuration:
// COD-001 reads what the server actually says and asserts accordingly, while
// COD-002 and COD-003 force each state through response interception, so the UI
// behaviour is pinned regardless of how the server happens to be configured.
//
// Deliberately NOT asserting "₹50" anywhere: matching the literal would pass
// against the very bug that was fixed.
const { test, expect } = require('../../../fixtures');
const db = require('../../../fixtures/db');
const { NavBar } = require('../../../pages/client/NavBar');
const { ProductPage } = require('../../../pages/client/ProductPage');
const { BagPage } = require('../../../pages/client/BagPage');
const { PaymentPage } = require('../../../pages/client/PaymentPage');
const addresses = require('../../../test-data/addresses');

/** Signs in, puts a product in the bag through the app, and lands on checkout. */
async function reachCheckout({ page, buyer, session, signIn, aProduct }) {
  await db.seedDefaultAddress(buyer, addresses.karnataka.stateCode, addresses.karnataka.pincode);
  await signIn(page, session, buyer);

  const product = new ProductPage(page);
  await product.goto(aProduct.uuid);
  const addToBag = page.waitForResponse(
    r => r.url().includes('/client/bag/add') && r.request().method() === 'POST');
  await product.buyNowClick();
  expect((await addToBag).ok(), 'adding to the bag must succeed').toBeTruthy();

  await expect(page).toHaveURL(/\/bag$/);
  await new BagPage(page).proceedToCheckout.click();
  await expect(page).toHaveURL(/\/payment$/);
}

/** Replaces the COD part of the cart preview with the given object (or null). */
async function stubCodCharge(page, mutate) {
  await page.route('**/client/bag/gst-preview', async route => {
    const response = await route.fetch();
    const body = await response.json();
    if (body?.data) mutate(body.data);
    await route.fulfill({ response, json: body });
  });
}

test.describe('The COD charge comes from the server @regression @critical', () => {

  test('COD-001 the screen agrees with the server, whichever state COD is in',
      async ({ page, api, buyer, session, signIn, aProduct }) => {

    await reachCheckout({ page, buyer, session, signIn, aProduct });

    const preview = await api.get('/client/bag/gst-preview', {
      headers: { Authorization: `Bearer ${session}` },
    });
    expect(preview.ok(), 'the GST preview must be readable').toBeTruthy();
    const quote = (await preview.json()).data;

    expect(quote.codCharge,
      'the server must quote the COD charge — the browser may not compute it').toBeTruthy();
    const cod = quote.codCharge;

    const payment = new PaymentPage(page);
    await expect(payment.deliveryAddressHeading).toBeVisible();
    await payment.chooseCashOnDelivery();

    if (!cod.available) {
      // The shipped state: no SAC and no tax basis are configured, so the server
      // will refuse a COD order. The screen must say so rather than quoting a
      // charge it cannot stand behind.
      expect(cod.taxResolution,
        'the reason must be diagnosable, not a bare refusal').toBeTruthy();
      await expect(payment.codUnavailable).toBeVisible();
      await expect(payment.codFee).toHaveCount(0,
        { message: 'no charge may be quoted while it cannot be priced' });
      await expect(payment.placeOrder).toBeDisabled();
      return;
    }

    // Configured: the server's arithmetic must hold, and the screen must match it.
    //
    // gross = taxable + tax, which is correct under BOTH tax bases — exclusive, where
    // the tax is added to the charge, and inclusive, where it is carved out of it.
    // Asserting `fee + tax` instead would be the exclusive formula, and would demand
    // that an inclusive ₹50 charge bill ₹57.63.
    const codGross = cod.taxablePaise + cod.taxPaise;
    expect(cod.totalPaise, 'the COD total must be the grand total plus the charge, tax included')
      .toBe(quote.grandTotal + codGross);
    expect(cod.cgstPaise + cod.sgstPaise + cod.igstPaise,
      'the heads must sum to the tax').toBe(cod.taxPaise);

    if (cod.taxInclusive) {
      expect(codGross, 'an inclusive charge grosses to exactly the configured fee')
        .toBe(cod.feePaise);
    } else {
      expect(codGross, 'an exclusive charge grosses to the fee plus its tax')
        .toBe(cod.feePaise + cod.taxPaise);
    }
    expect(cod.placeOfSupply, 'the quote must say what jurisdiction it was taxed in').toBeTruthy();

    await expect(payment.codFee).toBeVisible();
    expect(await payment.codFeePaise(),
      'the displayed COD fee must be the amount the server quoted').toBe(cod.feePaise);

    await expect(payment.dueNow).toBeVisible();
    expect(await payment.dueNowPaise(),
      'the displayed total must be the amount the server quoted').toBe(cod.totalPaise);

    if (cod.taxPaise > 0) {
      await expect(payment.codFeeGst).toBeVisible();
      expect(PaymentPage.paiseFrom(await payment.codFeeGst.innerText())).toBe(cod.taxPaise);
    }
  });

  test('COD-002 the page renders whatever the server says the charge is',
      async ({ page, buyer, session, signIn, aProduct }) => {

    // Force an available charge with figures no literal in the source could match.
    // If a hardcoded amount survived anywhere, the page would show that instead.
    await stubCodCharge(page, data => {
      data.codCharge = {
        available: true,
        serviceCode: 'COD_FEE',
        serviceName: 'Cash on Delivery Fee',
        sac: '999799',
        feePaise: 7777,
        taxablePaise: 7777,
        cgstPaise: 350,
        sgstPaise: 350,
        igstPaise: 0,
        taxPaise: 700,
        taxRateBp: 900,
        taxInclusive: false,
        placeOfSupply: '29',
        taxResolution: 'RULE_APPLIED',
        totalPaise: data.grandTotal + 7777 + 700,
      };
    });

    await reachCheckout({ page, buyer, session, signIn, aProduct });

    const payment = new PaymentPage(page);
    await expect(new NavBar(page).accountButton).toBeVisible();
    await payment.chooseCashOnDelivery();

    await expect(payment.codFee).toBeVisible();
    expect(await payment.codFeePaise(),
      'the page must render the charge the server reported').toBe(7777);
    expect(PaymentPage.paiseFrom(await payment.codFeeGst.innerText()),
      'and the tax the server reported').toBe(700);
    await expect(payment.placeOrder).toBeEnabled();
  });

  test('COD-003 an unavailable charge is refused on the screen, not quoted',
      async ({ page, buyer, session, signIn, aProduct }) => {

    // The server cannot price the charge — no SAC configured. It will refuse a COD
    // order, so the screen must not let the customer commit to one.
    await stubCodCharge(page, data => {
      data.codCharge = {
        available: false,
        serviceCode: 'COD_FEE',
        serviceName: 'Cash on Delivery Fee',
        sac: null,
        feePaise: 5000,
        taxablePaise: 5000,
        cgstPaise: 0, sgstPaise: 0, igstPaise: 0, taxPaise: 0,
        taxRateBp: null,
        taxInclusive: false,
        placeOfSupply: '29',
        taxResolution: 'NO_SAC_CONFIGURED',
        totalPaise: data.grandTotal,
      };
    });

    await reachCheckout({ page, buyer, session, signIn, aProduct });

    const payment = new PaymentPage(page);
    await payment.chooseCashOnDelivery();

    await expect(payment.codUnavailable).toBeVisible();
    // The amounts are present in the payload but must not be presented as a quote.
    await expect(payment.codFee).toHaveCount(0,
      { message: 'an unpriceable charge must not be shown as a charge' });
    await expect(payment.codFeeGst).toHaveCount(0,
      { message: 'and an unknown rate must not be rendered as ₹0 of tax' });
    await expect(payment.placeOrder).toBeDisabled(
      { message: 'the customer must not be able to commit to an order the server will refuse' });
  });
});
