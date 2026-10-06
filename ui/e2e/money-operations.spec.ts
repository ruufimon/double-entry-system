import { expect, test } from '@playwright/test';
import { activityRows, balanceCard, openAccount, uniqueAccountId } from './support';

test('deposits funds and records the activity on the dashboard', async ({ page, request }, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await openAccount(request, accountId, 50);

  await page.goto(`/accounts/${accountId}`);
  await page.getByRole('link', { name: /Deposit/ }).click();
  await expect(page).toHaveURL(`/accounts/${accountId}/deposit`);

  await page.getByLabel('Amount').fill('150.25');
  await page.getByRole('button', { name: 'Confirm deposit' }).click();

  await expect(page).toHaveURL(`/accounts/${accountId}`);
  await expect(balanceCard(page)).toHaveText('฿200.25');
  await expect(activityRows(page)).toHaveCount(2);
  const deposit = activityRows(page).first();
  await expect(deposit).toContainText('Deposit');
  await expect(deposit).toContainText('+฿150.25');
  await expect(deposit).toContainText('Balance ฿200.25');
});

test('withdraws funds and shows the remaining balance', async ({ page, request }, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await openAccount(request, accountId, 200);

  await page.goto(`/accounts/${accountId}`);
  await page.getByRole('link', { name: /Withdraw/ }).click();
  await expect(page).toHaveURL(`/accounts/${accountId}/withdraw`);

  await page.getByLabel('Amount').fill('75.50');
  await page.getByRole('button', { name: 'Confirm withdrawal' }).click();

  await expect(page.getByRole('heading', { name: 'Withdraw complete' })).toBeVisible();
  await expect(page.locator('.success-state strong')).toHaveText('฿124.50');

  await page.getByRole('link', { name: 'Return to account' }).click();
  await expect(balanceCard(page)).toHaveText('฿124.50');
  await expect(activityRows(page)).toHaveCount(2);
  const withdrawal = activityRows(page).filter({ hasText: 'Withdrawal' });
  await expect(withdrawal).toContainText('−฿75.50');
  await expect(withdrawal).toContainText('Balance ฿124.50');
});

test('rejects a withdrawal larger than the balance and keeps the balance unchanged', async ({
  page,
  request,
}, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await openAccount(request, accountId, 50);

  await page.goto(`/accounts/${accountId}/withdraw`);
  await page.getByLabel('Amount').fill('80');
  await page.getByRole('button', { name: 'Confirm withdrawal' }).click();

  const alert = page.getByRole('alert');
  await expect(alert).toContainText('insufficient_funds');
  await expect(page.getByRole('heading', { name: 'Withdraw complete' })).toBeHidden();

  await page.getByRole('link', { name: '← Account overview' }).click();
  await expect(balanceCard(page)).toHaveText('฿50.00');
  await expect(activityRows(page)).toHaveCount(1);
});

test('blocks invalid amounts before they reach the API', async ({ page, request }, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await openAccount(request, accountId);
  await page.goto(`/accounts/${accountId}/deposit`);

  const amount = page.getByLabel('Amount');
  const submit = page.getByRole('button', { name: 'Confirm deposit' });

  for (const invalid of ['0', '-5', '10.255', 'abc']) {
    await amount.fill(invalid);
    await amount.blur();
    await expect(submit).toBeDisabled();
    await expect(
      page.getByText('Enter a positive amount with no more than two decimal places.'),
    ).toBeVisible();
  }

  await amount.fill('10.25');
  await expect(submit).toBeEnabled();
});
