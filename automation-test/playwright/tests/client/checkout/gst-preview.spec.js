// A shopper who has not saved a delivery address yet.
//
// This is the ordinary first visit to the bag, and until P0-14 the server answered
// it with an internal error: the GST engine will not choose between CGST+SGST and
// IGST without a place of supply, and it refuses by throwing.
//
// The two things these pin are that the request is answered, and that the answer
// is not a zero. "The tax is not determinable yet" and "this order is taxed at 0%"
// are different statements, and a shopper must never be shown the second when the
// first is true — so the amounts come back null and the screen says a delivery
// address is needed rather than rendering ₹0.
//
// Deliberately NOT seeding an address: reachCheckout in cod-charge.spec.js does,
// which is why that file never met this bug.
const { test, expect } = require('../../../fixtures');
const { ProductPage } = require('../../../pages/client/ProductPage');
const { BagPage } = require('../../../pages/client/BagPage');

test.describe('The GST preview answers an addressless shopper @regression @gst', () => {

  test('GST-020 no delivery address yields an unresolved preview, not a server error',
      async ({ page, api, buyer, session, signIn, aProduct }) => {

    const preview = await api.get('/client/bag/gst-preview', {
      headers: { Authorization: `Bearer ${session}` },
    });

    expect(preview.status(),
      'an addressless shopper must get an answer, not a 500').toBe(200);
    const quote = (await preview.json()).data;

    expect(quote.gstResolved,
      'with no place of supply the tax cannot be determined').toBe(false);
    expect(quote.taxResolution,
      'and the reason must be diagnosable').toBe('NO_PLACE_OF_SUPPLY');

    // Null, not 0. Each of these as a zero would read as a genuine 0% rate.
    for (const field of ['cgstAmount', 'sgstAmount', 'igstAmount', 'totalGst', 'grandTotal']) {
      expect(quote[field], `${field} must be null, never 0, while the tax is unknown`).toBeNull();
    }
    expect(quote.supplyType, 'neither intra- nor inter-state is known').toBeNull();
  });

  test('GST-021 the bag asks for an address instead of showing ₹0 of GST',
      async ({ page, buyer, session, signIn, aProduct }) => {

    await signIn(page, session, buyer);

    const product = new ProductPage(page);
    await product.goto(aProduct.uuid);
    const addToBag = page.waitForResponse(
      r => r.url().includes('/client/bag/add') && r.request().method() === 'POST');
    await product.buyNowClick();
    expect((await addToBag).ok(), 'adding to the bag must succeed').toBeTruthy();

    await expect(page).toHaveURL(/\/bag$/);
    const bag = new BagPage(page);
    await expect(bag.proceedToCheckout).toBeVisible();

    // The existing copy, now driven by the server saying the tax is unresolved
    // rather than by the request having failed.
    await expect(page.getByText('Add a delivery address to see GST')).toBeVisible();

    // No tax claim anywhere on the summary: a "Total GST" line would be ₹0.
    await expect(page.getByText('Total GST')).toHaveCount(0,
      { message: 'an undetermined tax must not be totalled' });

    // And the order total is the subtotal — not subtotal + ₹0 dressed as a
    // tax-inclusive total. Both are read off the page, so a change to either breaks.
    const money = t => Math.round(parseFloat(String(t).replace(/[^0-9.]/g, '')) * 100);
    const summary = page.locator('aside').filter({ hasText: 'Order Summary' });
    const amountOn = row => summary.locator('div.flex')
      .filter({ has: page.getByText(row, { exact: true }) })
      .first().getByText(/^₹/).first();

    const subtotal = money(await amountOn('Subtotal').innerText());
    const total = money(await amountOn('Total').innerText());
    expect(subtotal, 'the bag must show a subtotal to compare against').toBeGreaterThan(0);
    expect(total, 'the total may only be the subtotal while GST is unknown').toBe(subtotal);
  });
});
