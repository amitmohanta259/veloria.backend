class ReturnsPage {
  constructor(page) { this.page = page; }
  async goto() { await this.page.goto('/admin/returns'); }
  metric(label) { return this.page.getByText(label, { exact: true }).locator('xpath=following-sibling::*[1]'); }
  get requestsTable() { return this.page.getByRole('table'); }
}
module.exports = { ReturnsPage };
