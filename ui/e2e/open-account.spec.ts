import { expect, test } from '@playwright/test';
import { uniqueAccountId } from './support';

test('opens a new account and shows its empty dashboard', async ({ page }, testInfo) => {
  const accountId = uniqueAccountId(testInfo);

  await page.goto('/');
  await page.getByLabel('Account ID').fill(accountId);
  await page.getByRole('button', { name: 'Continue to account' }).click();

  await expect(page).toHaveURL(`/accounts/${accountId}`);
  await expect(page.getByRole('heading', { name: accountId })).toBeVisible();
  await expect(page.getByText('Available balance')).toBeVisible();
  await expect(page.getByText('฿0.00', { exact: true })).toBeVisible();
  await expect(page.getByText('No activity has been recorded yet.')).toBeVisible();
});

test('rejects an invalid account ID', async ({ page }) => {
  await page.goto('/');
  const accountId = page.getByLabel('Account ID');
  await accountId.fill('not a valid id!');
  await accountId.blur();

  await expect(
    page.getByText('Use 1–64 letters, numbers, underscores, or hyphens.'),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Continue to account' })).toBeDisabled();
});

test('reopening an existing account keeps its balance', async ({ page, request }, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await request.put(`/api/accounts/${accountId}`, { data: {} });
  await request.post(`/api/accounts/${accountId}/deposits`, { data: { amount: 42 } });

  await page.goto('/');
  await page.getByLabel('Account ID').fill(accountId);
  await page.getByRole('button', { name: 'Continue to account' }).click();

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
