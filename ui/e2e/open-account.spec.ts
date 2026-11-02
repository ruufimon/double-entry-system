import { expect, test } from '@playwright/test';
import { uniqueAccountId } from './support';

test('selects a seeded account and shows its opening deposit', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Choose an account' })).toBeVisible();
  await expect(page.getByText('Each demo account starts with THB 100.00.')).toBeVisible();
  await expect(page.getByRole('link', { name: 'acc123' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'acc456' })).toBeVisible();
  await page.getByRole('link', { name: 'acc123' }).click();

  await expect(page).toHaveURL('/accounts/acc123');
  await expect(page.getByRole('heading', { name: 'acc123' })).toBeVisible();
  await expect(page.getByText('Available balance')).toBeVisible();
  await expect(
    page.locator('.activity-row').filter({ hasText: '+฿100.00' }),
  ).toContainText('Balance ฿100.00');
});

test('keeps the dashboard layout stable while account details load', async ({
  page,
  request,
}, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await request.put(`/api/accounts/${accountId}`, { data: {} });

  let releaseOverview!: () => void;
  const overviewGate = new Promise<void>((resolve) => {
    releaseOverview = resolve;
  });
  await page.route('**/api/**', async (route) => {
    const requestPath = new URL(route.request().url()).pathname;
    if (requestPath === `/api/accounts/${accountId}/overview`) {
      await overviewGate;
    }
    await route.continue();
  });

  await page.goto(`/accounts/${accountId}`);

  await expect(page.getByRole('status')).toHaveText('Loading account details…');
  await expect(page.locator('.account-skeleton .balance-layout')).toBeVisible();
  await expect(page.locator('.skeleton-activities .activity-row')).toHaveCount(3);

  releaseOverview();

  await expect(page.locator('.account-skeleton')).toBeHidden();
  await expect(page.getByText('Available balance')).toBeVisible();
  await expect(page.getByText('No activity has been recorded yet.')).toBeVisible();
});

test('home page offers selection instead of account creation', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByLabel('Select a demo account').getByRole('link')).toHaveCount(2);
  await expect(page.getByLabel('Account ID')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Continue to account' })).toHaveCount(0);
});

test('reopening an existing account keeps its balance', async ({ page, request }, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await request.put(`/api/accounts/${accountId}`, { data: {} });
  await request.post(`/api/accounts/${accountId}/deposits`, { data: { amount: 42 } });

  await page.goto(`/accounts/${accountId}`);

  await expect(page).toHaveURL(`/accounts/${accountId}`);
  await expect(page.locator('.balance-card strong')).toHaveText('฿42.00');
});

test('shows a first-deposit prompt for an account that does not exist', async ({ page }, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await page.goto(`/accounts/${accountId}`);

  await expect(page.getByRole('heading', { name: 'Start with your first deposit' })).toBeVisible();
  await page.getByRole('link', { name: 'Make a deposit' }).click();
  await expect(page).toHaveURL(`/accounts/${accountId}/deposit`);
});
