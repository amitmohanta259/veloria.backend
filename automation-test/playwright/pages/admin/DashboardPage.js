class DashboardPage {
  constructor(page) {
    this.page = page;
    this.heading = page.getByRole('heading', { name: 'Performance Dashboard' });
  }
  async goto() { await this.page.goto('/admin/dashboard'); }
  /** The big figure under a metric label. */
  metric(label) { return this.page.getByText(label, { exact: true }).locator('xpath=following-sibling::*[1]'); }
}
module.exports = { DashboardPage };
