class ProductPage {
  constructor(page) {
    this.page = page;
    this.buyNow = page.getByRole('button', { name: /buy now/i });
  }

  async goto(uuid) { await this.page.goto(`/product/${uuid}`); }
  async buyNowClick() { await this.buyNow.click(); }
}
module.exports = { ProductPage };
