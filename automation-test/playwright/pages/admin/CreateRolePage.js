class CreateRolePage {
  constructor(page) {
    this.page = page;
    this.department = page.getByRole('combobox').first();
    this.grantAll = page.getByRole('button', { name: /grant all/i });
    // Labels are title case in the DOM; the capitals on screen are CSS.
    this.moduleLabels = ['Dashboard', 'Analytics', 'Inventory', 'Staff', 'Sales & Orders', 'Suppliers', 'Customers',
      'Financials', 'Financials Report', 'Indicators', 'Expenses', 'Salary Payment', 'Returns',
      'GST Management', 'GST Accounting', 'GST Tracker', 'GST Compliance', 'Business Details', 'Settings'];
    // Scoped to <main>: the sidebar repeats many of these names.
    this.main = page.getByRole('main');
    this.moduleRows = this.main.getByText(new RegExp('^(' + this.moduleLabels.map(l => l.replace(/[&]/g, '\\$&')).join('|') + ')$'));
  }
  async goto() { await this.page.goto('/admin/settings/roles/create'); }
}
module.exports = { CreateRolePage };
