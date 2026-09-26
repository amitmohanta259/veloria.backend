class RolesPage {
  constructor(page) {
    this.page = page;
    this.createNewRole = page.getByRole('button', { name: /create new role/i });
  }
  async goto() { await this.page.goto('/admin/settings'); }
  /** "n / 19 modules" cells. */
  get moduleCounts() { return this.page.getByText(/\d+ \/ \d+ modules/); }
  async openPermissionsFor(scopeLabel) {
    await this.page.getByRole('row').filter({ hasText: scopeLabel }).getByRole('button', { name: /permissions/i }).click();
  }
}
module.exports = { RolesPage };
