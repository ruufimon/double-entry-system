import { expect, test } from '@playwright/test';

test('opens a new account and shows its empty dashboard', async ({ page }, testInfo) => {
  const accountId = `e2e-${Date.now()}-${testInfo.workerIndex}`;

  await page.goto('/');
  await page.getByLabel('Account ID').fill(accountId);
  await page.getByRole('button', { name: 'Continue to account' }).click();

  await expect(page).toHaveURL(`/accounts/${accountId}`);
  await expect(page.getByRole('heading', { name: accountId })).toBeVisible();
  await expect(page.getByText('Available balance')).toBeVisible();
  await expect(page.getByText('฿0.00', { exact: true })).toBeVisible();
  await expect(page.getByText('No activity has been recorded yet.')).toBeVisible();
});
