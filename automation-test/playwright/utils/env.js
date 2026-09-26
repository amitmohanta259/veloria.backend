/**
 * Every environment-specific value in one place. Nothing else reads
 * process.env, so pointing the suite at another environment is one file
 * (or a handful of exported variables in CI).
 */
const get = (name, fallback) => {
  const v = process.env[name];
  return v && v.trim() ? v.trim() : fallback;
};

module.exports = {
  CLIENT_URL: get('CLIENT_URL', 'http://localhost:5174'),
  ADMIN_URL: get('ADMIN_URL', 'http://localhost:5173'),
  API_URL: get('API_URL', 'http://localhost:8081/api/master'),

  DB_HOST: get('VELORIA_DB_HOST', 'localhost'),
  DB_PORT: Number(get('VELORIA_DB_PORT', '5432')),
  DB_NAME: get('VELORIA_DB_NAME', 'postgres'),
  DB_USER: get('VELORIA_DB_USER', 'postgres'),
  DB_PASSWORD: get('VELORIA_DB_PASSWORD', 'postgres'),

  // Only the real sign-in journey needs these. Absent → that test is skipped.
  TEST_USERNAME: get('TEST_USERNAME', ''),
  TEST_PASSWORD: get('TEST_PASSWORD', ''),
};
