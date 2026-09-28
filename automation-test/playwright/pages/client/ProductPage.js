class ProductPage {
  constructor(page) {
    this.page = page;
    this.buyNow = page.getByRole('button', { name: /buy now/i });
  }

  /**
   * Opens a product and waits until Buy Now will actually do something.
   *
   * The view loads in two requests: the product, then its per-size stock. Buy Now
   * is painted after the first but reads state the second fills in — it refuses to
   * add anything while a product has sizes and none is chosen, and the size is
   * auto-chosen from that second response. Clicking in between is a click that
   * silently does nothing, and the test then fails 10 seconds later waiting for a
   * POST that was never going to be sent.
   *
   * Waiting for the sizes response is waiting for the app's own readiness signal,
   * so this is not a sleep: a slow dev server makes it wait longer, not flake.
   */
  async goto(uuid) {
    const sizes = this.page.waitForResponse(
      r => r.url().includes(`/client/products/${uuid}/sizes`),
      { timeout: 15000 },
    ).catch(() => null);   // A product with no size rows never fires it.
    await this.page.goto(`/product/${uuid}`);
    await this.buyNow.waitFor({ state: 'visible' });
    await sizes;
  }

  async buyNowClick() { await this.buyNow.click(); }
}
module.exports = { ProductPage };
