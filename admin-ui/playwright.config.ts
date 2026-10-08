import { defineConfig, devices } from '@playwright/test';

const stagingUrl = process.env['E2E_BASE_URL']?.trim().replace(/\/+$/, '');
const remoteBaseUrl = stagingUrl
  ? /^https?:\/\//i.test(stagingUrl)
    ? stagingUrl
    : `https://${stagingUrl}${stagingUrl.includes('.') ? '' : '.up.railway.app'}`
  : undefined;
const localBaseUrl = 'http://localhost:4300';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env['CI']),
  reporter: 'list',
  use: {
    baseURL: remoteBaseUrl ?? localBaseUrl,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: remoteBaseUrl ? undefined : [
    {
      name: 'Scalatra API',
      command: 'sbt run',
      cwd: '..',
      url: 'http://localhost:8080/ping',
      reuseExistingServer: !process.env['CI'],
      timeout: 120_000,
    },
    {
      name: 'Angular Admin UI',
      command:
        './node_modules/.bin/ng serve --proxy-config proxy.conf.json --host localhost --port 4300',
      url: localBaseUrl,
      reuseExistingServer: !process.env['CI'],
      timeout: 120_000,
    },
  ],
});
