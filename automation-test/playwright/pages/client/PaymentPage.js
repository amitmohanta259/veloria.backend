class PaymentPage {
  constructor(page) {
    this.page = page;
    // Exact, because the section's placeholder reads "Select a delivery address"
    // — a substring match found both whenever no address was selected yet, and a
    // strict-mode violation there reported an ambiguous locator rather than the
    // address not having loaded.
    this.deliveryAddressHeading = page.getByText('Delivery Address', { exact: true });
    // The section's own button, not any text containing the phrase: the
    // unavailable notice also reads "Cash on delivery is currently...", which
    // made a getByText locator ambiguous.
    this.cashOnDelivery = page.getByRole('button', { name: /Cash on Delivery/i });
    this.placeOrder = page.getByRole('button', { name: /^place order$/i });

    // The money the screen shows. Addressed by test id rather than by text,
    // because what these must prove is that the figures come from the server —
    // matching on a hardcoded "₹50" would pass against the very bug that was
    // fixed.
    this.codFee = page.getByTestId('cod-fee');
    this.codFeeGst = page.getByTestId('cod-fee-gst');
    this.dueNow = page.getByTestId('due-now');
    this.codUnavailable = page.getByTestId('cod-unavailable');
    this.codAmountDue = page.getByTestId('cod-amount-due');
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
