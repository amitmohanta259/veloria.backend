class GstManagementPage {
  constructor(page) {
    this.page = page;
    this.rulesTab = page.getByRole('button', { name: /^tax rules$/i });
    this.calculatorTab = page.getByRole('button', { name: /^calculator$/i });
    this.calculate = page.getByRole('button', { name: /calculate gst/i });
    this.rulesTable = page.getByRole('table');
  }

  async goto() { await this.page.goto('/admin/gst'); }
  async openCalculator() { await this.calculatorTab.click(); }

  /** Inputs are labelled by an adjacent <label>, not a for/id pair. */
  field(label) { return this.page.locator(`label:has-text("${label}") + input`); }

  async fillCalculator({ hsn, price, buyerState, sellerState }) {
    await this.field('HSN Code').fill(hsn);
    await this.field('Selling Price').fill(price);
    await this.field('Buyer State').fill(buyerState);
    await this.field('Seller State').fill(sellerState);
  }
}
module.exports = { GstManagementPage };
