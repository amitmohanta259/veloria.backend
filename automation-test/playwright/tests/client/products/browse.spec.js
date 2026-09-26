// PROD-007, PROD-008 — the catalogue is open to everyone.
const { test, expect } = require('../../../fixtures');
const { CollectionPage } = require('../../../pages/client/CollectionPage');
const { ProductPage } = require('../../../pages/client/ProductPage');

test.describe('Browsing without signing in @regression', () => {
  test('PROD-007 the collection shows product cards @smoke', async ({ page }) => {
    const collection = new CollectionPage(page);
    await collection.goto();
    await expect(collection.cards.first()).toBeVisible();
    expect(await collection.cards.count()).toBeGreaterThan(0);
  });

  test('PROD-008 a product page shows its name, price and Buy Now @smoke', async ({ page, aProduct }) => {
    const product = new ProductPage(page);
    await product.goto(aProduct.uuid);
    await expect(page.getByText(aProduct.name).first()).toBeVisible();
    await expect(page.getByText(/₹\s?[\d,]+/).first()).toBeVisible();
    await expect(product.buyNow).toBeVisible();
  });
});
