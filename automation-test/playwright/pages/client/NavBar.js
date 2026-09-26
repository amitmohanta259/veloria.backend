/** The client header: the one place the signed-in / signed-out state is visible. */
class NavBar {
  constructor(page) {
    this.page = page;
    this.signInButton = page.getByTitle('Sign in');
    this.accountButton = page.getByTitle('My account');
  }

  async openAccountMenu() { await this.accountButton.click(); }
}
module.exports = { NavBar };
