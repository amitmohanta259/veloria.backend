// Verifies cash on delivery is configured before the browser suite runs.
//
// Since P0-13 the shipped configuration IS the approved one — ₹50 inclusive under
// SAC 998599, priced by a rule the migration seeds — so this no longer invents a
// service code. It checks, and fails loudly if the migration has not been applied,
// because a suite that silently ran against unconfigured COD would report the
// unavailable path as if it were the configured one.
//
// Kept as a global hook rather than per-spec setup: the configuration is global,
// single-tenant state, and specs that set it up around themselves raced each other
// under `fullyParallel` — one file's teardown wiped it while another file's checkout
// was mid-flight, and Place Order went dead for no reason visible in the failing test.
const { Pool } = require('pg');
const env = require('./utils/env');

async function globalSetup() {
  const pool = new Pool({
    host: env.DB_HOST, port: env.DB_PORT, database: env.DB_NAME,
    user: env.DB_USER, password: env.DB_PASSWORD, max: 2, allowExitOnIdle: true,
  });
  const q = (sql, params = []) => pool.query(sql, params);

  try {
    const { rows: cfg } = await q(
      `SELECT config_key, config_value FROM gst_configuration
        WHERE config_key IN ('COD_ENABLED','COD_FEE_PAISE','COD_FEE_SAC','COD_FEE_TAX_BASIS')
          AND organization_id = 1`);
    const byKey = Object.fromEntries(cfg.map(r => [r.config_key, r.config_value]));

    const { rows: rule } = await q(
      `SELECT cgst_rate_bp, sgst_rate_bp, igst_rate_bp FROM gst_tax_rules
        WHERE hsn_code = $1 AND tax_code_type = 'SAC' AND active`, [byKey.COD_FEE_SAC ?? '']);

    const problems = [];
    if (byKey.COD_ENABLED !== 'true') problems.push('COD_ENABLED is not true');
    if (!byKey.COD_FEE_PAISE) problems.push('COD_FEE_PAISE is not set');
    if (!byKey.COD_FEE_SAC) problems.push('COD_FEE_SAC is not set');
    if (!byKey.COD_FEE_TAX_BASIS) problems.push('COD_FEE_TAX_BASIS is not set');
    if (!rule.length) problems.push(`no active SAC rule for ${byKey.COD_FEE_SAC}`);

    if (problems.length) {
      throw new Error(
        'Cash on delivery is not configured, so the COD checkout tests would exercise '
        + 'the unavailable path instead of the configured one. Apply the migrations '
        + '(CodServiceTaxP014, CodGstServiceRuleP013) first.\n  ' + problems.join('\n  '));
    }

    console.log(`[global-setup] COD configured: SAC ${byKey.COD_FEE_SAC}, `
      + `${byKey.COD_FEE_TAX_BASIS}, ${byKey.COD_FEE_PAISE} paise, `
      + `${(rule[0].cgst_rate_bp + rule[0].sgst_rate_bp) / 100}% intra-state`);
  } finally {
    await pool.end();
  }
}

module.exports = globalSetup;
