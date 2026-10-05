import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: Boolean(process.env['CI']),
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:4200',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: [
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
      command: 'bun run start --host localhost --port 4200',
      url: 'http://localhost:4200',
      reuseExistingServer: !process.env['CI'],
      timeout: 120_000,
    },
  ],
});
