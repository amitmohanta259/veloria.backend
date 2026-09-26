// @ts-check
const { defineConfig, devices } = require('@playwright/test');
const env = require('./utils/env');

/**
 * Two applications, two projects per browser: the shopper-facing client
 * (tests/client) and the management portal (tests/admin). Chromium runs by
 * default; the full matrix is `npm run test:cross-browser`.
 *
 * Failure artefacts only: a trace and a screenshot when a test fails, no
 * video. Retries are for CI flakiness at the infrastructure level, never a
 * substitute for a deterministic test — locally there are none.
 */
module.exports = defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    ['json', { outputFile: 'test-results/results.json' }],
  ],
  use: {
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    // ── Chromium (default) ────────────────────────────────────────────────
    { name: 'chromium', testDir: './tests/client', use: { ...devices['Desktop Chrome'], baseURL: env.CLIENT_URL } },
    { name: 'chromium-admin', testDir: './tests/admin', use: { ...devices['Desktop Chrome'], baseURL: env.ADMIN_URL } },
    // ── Cross-browser: critical journeys only, selected with --project ────
    { name: 'firefox', testDir: './tests/client', grep: /@critical/, use: { ...devices['Desktop Firefox'], baseURL: env.CLIENT_URL } },
    { name: 'webkit', testDir: './tests/client', grep: /@critical/, use: { ...devices['Desktop Safari'], baseURL: env.CLIENT_URL } },
  ],
});
