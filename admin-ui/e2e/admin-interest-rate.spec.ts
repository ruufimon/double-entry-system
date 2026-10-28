import { expect, test } from '@playwright/test';

const adminPassword = process.env['ADMIN_PASSWORD'] ?? 'e2e-only-admin-password';
const nextMonth = (() => {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: 'numeric',
  }).formatToParts(new Date());
  const year = Number(parts.find((part) => part.type === 'year')?.value);
  const month = Number(parts.find((part) => part.type === 'month')?.value);
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Bangkok',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(new Date(Date.UTC(year, month, 1)));
})();

test('admin reviews and saves a rate that takes effect next month', async ({ page, request }) => {
  const unauthenticated = await request.get('/api/admin/interest-rate');
  expect(unauthenticated.status()).toBe(401);

  await page.goto('/interest-rate');
  await expect(page.getByRole('heading', { name: 'Admin sign-in' })).toBeVisible();
  await page.getByLabel('Admin password').fill('incorrect-password');
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByRole('alert')).toContainText('The admin password is incorrect. Try again.');
  await expect(page.getByRole('heading', { name: 'Admin sign-in' })).toBeVisible();
  await page.getByLabel('Admin password').fill(adminPassword);
  await page.getByRole('button', { name: 'Continue' }).click();

  await expect(page.getByRole('heading', { name: 'Interest rate' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'What changes' })).toBeVisible();
  await page.getByLabel('Annual interest rate').fill('3.65');
  const impact = page.getByRole('table').nth(0);
  await expect(impact).toContainText('0.00%');
  await expect(impact).toContainText('3.65%');
  await expect(impact).toContainText(nextMonth);

  await page.getByRole('button', { name: 'Save rate' }).click();
  await expect(page.getByRole('status')).toContainText(`3.65% scheduled to take effect ${nextMonth}.`);
  await expect(page.getByRole('table').last()).toContainText('Scheduled');
});
