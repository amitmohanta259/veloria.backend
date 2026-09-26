class OrderConfirmationPage {
  constructor(page) {
    this.page = page;
    this.confirmed = page.getByText(/your order is confirmed/i);
  }
}
module.exports = { OrderConfirmationPage };
