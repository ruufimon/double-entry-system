import { defineConfig, devices } from '@playwright/test';

const uiPort = process.env['UI_PORT'] ?? '4200';
const stagingUrl = process.env['E2E_BASE_URL']?.trim().replace(/\/+$/, '');
const remoteBaseUrl = stagingUrl
  ? /^https?:\/\//i.test(stagingUrl)
    ? stagingUrl
    : `https://${stagingUrl}${stagingUrl.includes('.') ? '' : '.up.railway.app'}`
  : undefined;
const localBaseUrl = `http://localhost:${uiPort}`;

// The same specs also cover the Elm port of this app: E2E_APP=ui-elm.
const e2eApp = process.env['E2E_APP'] ?? 'ui';
const uiServer =
  e2eApp === 'ui'
    ? {
        name: 'Angular UI',
        command:
          `./node_modules/.bin/ng serve --proxy-config proxy.conf.json --host localhost --port ${uiPort}`,
        cwd: '.',
      }
    : {
        name: `${e2eApp} UI`,
        command: `./node_modules/.bin/vite --host localhost --port ${uiPort} --strictPort`,
        cwd: `../${e2eApp}`,
      };

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
      ...uiServer,
      url: localBaseUrl,
      reuseExistingServer: !process.env['CI'],
      timeout: 120_000,
    },
  ],
});
