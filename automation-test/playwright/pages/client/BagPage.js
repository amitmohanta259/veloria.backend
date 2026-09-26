class BagPage {
  constructor(page) {
    this.page = page;
    this.proceedToCheckout = page.getByRole('button', { name: /proceed to checkout/i });
  }

  async goto() { await this.page.goto('/bag'); }

  /** The quantity stepper for the line holding this product name. */
  line(productName) {
    return this.page.locator('div, article, li').filter({ hasText: productName }).filter({ has: this.page.getByRole('button', { name: '+' }) }).first();
  }

  async increase(productName) { await this.line(productName).getByRole('button', { name: '+' }).click(); }
  quantityOf(productName) { return this.line(productName).locator('span.w-8'); }
}
module.exports = { BagPage };
