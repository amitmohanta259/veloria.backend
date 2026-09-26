class LoginPage {
  constructor(page) {
    this.page = page;
    this.emailInput = page.getByPlaceholder('name@example.com');
    this.passwordInput = page.getByPlaceholder('••••••••');
    this.submit = page.getByRole('button', { name: /sign in/i });
  }

  async goto() { await this.page.goto('/login'); }

  async login(email, password) {
    await this.emailInput.fill(email);
    await this.passwordInput.fill(password);
    await this.submit.click();
  }
}
module.exports = { LoginPage };
