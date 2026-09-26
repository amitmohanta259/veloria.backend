class CollectionPage {
  constructor(page) {
    this.page = page;
    this.cards = page.locator('.product-card');
  }

  async goto() { await this.page.goto('/collection'); }

  /** Opens the card for a product by its image's accessible name. */
  async open(productName) {
    await this.cards.filter({ has: this.page.getByRole('img', { name: productName }) }).first().click();
  }

  async openFirst() { await this.cards.first().click(); }
}
module.exports = { CollectionPage };
