import { expect, test } from '@playwright/test';

test('lists accounts and opens complete operational detail', async ({ page, request }, testInfo) => {
  const accountId = `admin-e2e-${Date.now()}-${testInfo.workerIndex}-${testInfo.retry}`;
  const created = await request.put(`/api/accounts/${accountId}`, { data: {} });
  expect(created.ok()).toBe(true);
  const deposited = await request.post(`/api/accounts/${accountId}/deposits`, {
    data: { amount: 125 },
  });
  expect(deposited.ok()).toBe(true);
  const withdrawn = await request.post(`/api/accounts/${accountId}/withdrawals`, {
    data: { amount: 25 },
  });
  expect(withdrawn.ok()).toBe(true);

  await page.goto('/accounts');
  await expect(page.getByText('Unauthenticated demo admin')).toBeVisible();

  const row = page.getByRole('row').filter({ hasText: accountId });
  await expect(row).toContainText('฿100.00');
  await expect(row).toContainText('2 entries');
  await row.getByRole('link', { name: 'View details' }).click();

  await expect(page).toHaveURL(`/accounts/${accountId}`);
  await expect(page.getByRole('heading', { name: accountId })).toBeVisible();
  await expect(page.locator('.metric-card.accent strong')).toHaveText('฿100.00');
  const activityRows = page.locator('.activity-table tbody tr');
  await expect(activityRows).toHaveCount(2);
  await expect(activityRows.first()).toContainText('Withdrawal');
  await expect(activityRows.last()).toContainText('Deposit');
});

test('opens a specific account directly by ID', async ({ page, request }, testInfo) => {
  const accountId = `admin-lookup-${Date.now()}-${testInfo.workerIndex}-${testInfo.retry}`;
  const created = await request.put(`/api/accounts/${accountId}`, { data: {} });
  expect(created.ok()).toBe(true);

  await page.goto('/accounts');
  await page.getByLabel('Account ID to view').fill(accountId);
  await page.getByRole('button', { name: 'View Detail' }).click();

  await expect(page).toHaveURL(`/accounts/${accountId}`);
  await expect(page.getByRole('heading', { name: accountId })).toBeVisible();
  await expect(page.getByText('No activity recorded')).toBeVisible();
});
