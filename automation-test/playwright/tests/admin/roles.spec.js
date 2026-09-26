// ROLE-006, ROLE-007 — the permissions UI covers every module and shows real counts.
const { test, expect } = require('../../fixtures');
const { RolesPage } = require('../../pages/admin/RolesPage');
const { CreateRolePage } = require('../../pages/admin/CreateRolePage');

test.describe('Roles and permissions @regression @admin', () => {
  test('ROLE-006 the create-role page offers every module and the Finance department @smoke', async ({ page }) => {
    const create = new CreateRolePage(page);
    await create.goto();
    await expect(create.moduleRows).toHaveCount(19);
    for (const m of ['GST Management', 'GST Accounting', 'GST Tracker', 'GST Compliance']) {
      await expect(create.main.getByText(m, { exact: true })).toBeVisible();
    }
    await expect(create.department.locator('option', { hasText: 'Finance' })).toHaveCount(1);
  });

  test('ROLE-007 the roles table reports real grant counts', async ({ page, api }) => {
    const rolesRes = await api.get('/roles');
    const roles = (await rolesRes.json()).data;
    const granted = roles.find(r => r.permissions.some(p => p.canView || p.canCreate || p.canEdit || p.canDelete));
    test.skip(!granted, 'no role with any grant exists to check against');

    const rolesPage = new RolesPage(page);
    await rolesPage.goto();
    await expect(rolesPage.moduleCounts.first()).toBeVisible();
    const n = granted.permissions.filter(p => p.canView || p.canCreate || p.canEdit || p.canDelete).length;
    await expect(page.getByRole('row').filter({ hasText: granted.scopeLabel }).getByText(`${n} / 19 modules`)).toBeVisible();
  });
});
