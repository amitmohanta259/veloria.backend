class OrderHistoryPage {
  constructor(page) { this.page = page; }
  async goto() { await this.page.goto('/order-history'); }
  order(orderCode) { return this.page.getByText(orderCode); }
}
module.exports = { OrderHistoryPage };
