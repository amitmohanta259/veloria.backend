// The checkout screen must show the server's money, not its own.
//
// Until P0-12 the payment screen added a ₹50 literal of its own and rendered the
// COD total as `grandTotal + 50`. The ₹50 charge is approved, but its tax
// treatment is not — so the moment the charge carries tax that literal understates
// what the customer is charged, and a customer shown one figure and charged another
// is a defect however small the gap.
//
// These compare what the page displays against what the backend says, so the two
// cannot drift again. Deliberately NOT asserting "₹50" anywhere: matching on the
// literal would pass against the very bug that was fixed.
const { test, expect } = require('../../../fixtures');
const db = require('../../../fixtures/db');
const { NavBar } = require('../../../pages/client/NavBar');
const { ProductPage } = require('../../../pages/client/ProductPage');
const { BagPage } = require('../../../pages/client/BagPage');
const { PaymentPage } = require('../../../pages/client/PaymentPage');
const addresses = require('../../../test-data/addresses');

test.describe('The COD charge comes from the server @regression @critical', () => {

  test('COD-001 the displayed COD fee and total are exactly the backend figures',
      async ({ page, api, buyer, session, signIn, aProduct }) => {

    await db.seedDefaultAddress(buyer, addresses.karnataka.stateCode, addresses.karnataka.pincode);
    await signIn(page, session, buyer);

    // Put something in the bag through the application, so the quote is for a
    // real cart rather than one this test invented.
    const product = new ProductPage(page);
    await product.goto(aProduct.uuid);
    const addToBag = page.waitForResponse(
      r => r.url().includes('/client/bag/add') && r.request().method() === 'POST');
    await product.buyNowClick();
    expect((await addToBag).ok(), 'adding to the bag must succeed').toBeTruthy();

    await expect(page).toHaveURL(/\/bag$/);
    await new BagPage(page).proceedToCheckout.click();
    await expect(page).toHaveURL(/\/payment$/);

    // What the server says this cart costs, including cash on delivery.
    const preview = await api.get('/client/bag/gst-preview', {
      headers: { Authorization: `Bearer ${session}` },
    });
    expect(preview.ok(), 'the GST preview must be readable').toBeTruthy();
    const quote = (await preview.json()).data;

    expect(quote.codCharge,
      'the server must quote the COD charge — the browser may not compute it').toBeTruthy();
    const { feePaise, taxPaise, taxResolution, totalPaise } = quote.codCharge;

    // The server's own arithmetic must hold before the UI is judged against it.
    expect(totalPaise,
      'the COD total must be the grand total plus the charge and its tax')
      .toBe(quote.grandTotal + feePaise + taxPaise);

    const payment = new PaymentPage(page);
    await expect(payment.deliveryAddressHeading).toBeVisible();
    await payment.chooseCashOnDelivery();

    // The charge, as displayed, is the server's figure.
    await expect(payment.codFee).toBeVisible();
    expect(await payment.codFeePaise(),
      'the displayed COD fee must be the amount the server quoted').toBe(feePaise);

    // And so is the total the customer is asked for.
    await expect(payment.dueNow).toBeVisible();
    expect(await payment.dueNowPaise(),
      'the displayed total must be the amount the server quoted').toBe(totalPaise);

    // The tax line appears when, and only when, the tax is actually known. An
    // unresolved rate is not a zero rate, and the page must not render one as the
    // other.
    if (taxResolution === 'RULE_APPLIED' && taxPaise > 0) {
      await expect(payment.codFeeGst).toBeVisible();
      expect(PaymentPage.paiseFrom(await payment.codFeeGst.innerText())).toBe(taxPaise);
    } else {
      await expect(payment.codFeeGst).toHaveCount(0,
        { message: 'no GST line may be shown while the COD rate is unresolved' });
      expect(taxPaise, 'and an unresolved charge carries no tax figure').toBe(0);
    }
  });

  test('COD-002 the page contains no hardcoded COD amount of its own',
      async ({ page, buyer, session, signIn, aProduct }) => {

    await db.seedDefaultAddress(buyer, addresses.karnataka.stateCode, addresses.karnataka.pincode);
    await signIn(page, session, buyer);

    const product = new ProductPage(page);
    await product.goto(aProduct.uuid);
    await product.buyNowClick();
    await expect(page).toHaveURL(/\/bag$/);
    await new BagPage(page).proceedToCheckout.click();
    await expect(page).toHaveURL(/\/payment$/);

    const payment = new PaymentPage(page);
    await expect(new NavBar(page).accountButton).toBeVisible();
    await payment.chooseCashOnDelivery();

    // Break the server's answer and the page must follow it, not fall back to a
    // figure of its own. If a literal were still in the component, the displayed
    // fee would stay at the old ₹50 while the server said something else.
    await page.route('**/client/bag/gst-preview', async route => {
      const response = await route.fetch();
      const body = await response.json();
      if (body?.data?.codCharge) {
        body.data.codCharge.feePaise = 7777;
        body.data.codCharge.totalPaise = body.data.grandTotal + 7777;
        body.data.codCharge.taxPaise = 0;
        body.data.codCharge.taxResolution = 'NOT_CONFIGURED';
      }
      await route.fulfill({ response, json: body });
    });
    await page.reload();
    await payment.chooseCashOnDelivery();

    await expect(payment.codFee).toBeVisible();
    expect(await payment.codFeePaise(),
      'the page must render whatever the server says the charge is').toBe(7777);
  });
});
