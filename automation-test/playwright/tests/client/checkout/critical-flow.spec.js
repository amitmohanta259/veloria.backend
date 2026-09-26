// ORD-011 — the primary commerce journey, end to end, with state checked at every stage.
//
// Tagged @mutates: it places a real order, which the application records in the
// books and the GST subledger. The buyer fixture removes everything it created,
// but run this against a disposable database, never a shared one.
const { test, expect } = require('../../../fixtures');
const db = require('../../../fixtures/db');
const { NavBar } = require('../../../pages/client/NavBar');
const { CollectionPage } = require('../../../pages/client/CollectionPage');
const { ProductPage } = require('../../../pages/client/ProductPage');
const { BagPage } = require('../../../pages/client/BagPage');
const { PaymentPage } = require('../../../pages/client/PaymentPage');
const { OrderConfirmationPage } = require('../../../pages/client/OrderConfirmationPage');
const { OrderHistoryPage } = require('../../../pages/client/OrderHistoryPage');
const addresses = require('../../../test-data/addresses');

test.describe('Critical flow @regression @mutates @critical', () => {
  test('ORD-011 browse → product → Buy Now → bag → checkout → order confirmed → in history', async ({ page, api, buyer, session, signIn, aProduct }) => {
    await db.seedDefaultAddress(buyer, addresses.karnataka.stateCode, addresses.karnataka.pincode);
    await signIn(page, session, buyer);

    // Browse: signed in, the catalogue is visible.
    const collection = new CollectionPage(page);
    await collection.goto();
    await expect(new NavBar(page).accountButton).toBeVisible();
    await expect(collection.cards.first()).toBeVisible();

    // Open the product and buy it.
    const product = new ProductPage(page);
    await product.goto(aProduct.uuid);
    await expect(page.getByText(aProduct.name).first()).toBeVisible();
    const addToBag = page.waitForResponse(r => r.url().includes('/client/bag/add') && r.request().method() === 'POST');
    await product.buyNowClick();
    expect((await addToBag).ok(), 'adding to the bag must succeed').toBeTruthy();

    // The bag holds the product; go to checkout.
    await expect(page).toHaveURL(/\/bag$/);
    const bag = new BagPage(page);
    await expect(page.getByText(aProduct.name).first()).toBeVisible();
    await expect(bag.proceedToCheckout).toBeVisible();
    await bag.proceedToCheckout.click();

    // Checkout: an address is offered, choose cash on delivery, place the order.
    await expect(page).toHaveURL(/\/payment$/);
    const payment = new PaymentPage(page);
    await expect(payment.deliveryAddressHeading).toBeVisible();
    await payment.chooseCashOnDelivery();
    const placed = page.waitForResponse(r => r.url().includes('/client/order/place') && r.request().method() === 'POST');
    await payment.placeOrderClick();
    const placeRes = await placed;
    expect(placeRes.ok(), 'the order must be accepted').toBeTruthy();
    const orderCode = (await placeRes.json()).data.orderCode;
    expect(orderCode).toMatch(/^VO-/);

    // Confirmation, then history.
    await expect(page).toHaveURL(/\/order-confirmation$/);
    await expect(new OrderConfirmationPage(page).confirmed).toBeVisible();

    const history = new OrderHistoryPage(page);
    await history.goto();
    await expect(history.order(orderCode)).toBeVisible();

    // And the server agrees with what the screen showed.
    const detail = await api.get(`/client/order/${orderCode}`, { headers: { Authorization: `Bearer ${session}` } });
    expect(detail.ok()).toBeTruthy();
    expect((await detail.json()).data.orderCode).toBe(orderCode);
  });
});
