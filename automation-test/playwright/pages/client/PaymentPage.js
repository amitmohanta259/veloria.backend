class PaymentPage {
  constructor(page) {
    this.page = page;
    this.deliveryAddressHeading = page.getByText('Delivery Address');
    this.cashOnDelivery = page.getByText('Cash on Delivery');
    this.placeOrder = page.getByRole('button', { name: /^place order$/i });

    // The money the screen shows. Addressed by test id rather than by text,
    // because what these must prove is that the figures come from the server —
    // matching on a hardcoded "₹50" would pass against the very bug that was
    // fixed.
    this.codFee = page.getByTestId('cod-fee');
    this.codFeeGst = page.getByTestId('cod-fee-gst');
    this.dueNow = page.getByTestId('due-now');
  }

  async chooseCashOnDelivery() { await this.cashOnDelivery.click(); }
  async placeOrderClick() { await this.placeOrder.click(); }

  /** A displayed "₹1,23,456.78" as paise, so it can be compared with the server. */
  static paiseFrom(text) {
    const digits = String(text).replace(/[^0-9.]/g, '');
    return Math.round(parseFloat(digits) * 100);
  }

  async dueNowPaise() { return PaymentPage.paiseFrom(await this.dueNow.innerText()); }
  async codFeePaise() { return PaymentPage.paiseFrom(await this.codFee.innerText()); }
}
module.exports = { PaymentPage };
