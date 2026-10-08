import { defineConfig, devices } from '@playwright/test';

const uiPort = process.env['UI_PORT'] ?? '4200';
const remoteBaseUrl = process.env['E2E_BASE_URL']?.replace(/\/+$/, '');
const localBaseUrl = `http://localhost:${uiPort}`;

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
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
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
      name: 'Angular UI',
      command:
        `./node_modules/.bin/ng serve --proxy-config proxy.conf.json --host localhost --port ${uiPort}`,
      url: localBaseUrl,
      reuseExistingServer: !process.env['CI'],
      timeout: 120_000,
    },
  ],
});
