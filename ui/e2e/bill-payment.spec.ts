import { expect, test } from '@playwright/test';
import { activityRows, balanceCard, openAccount, uniqueAccountId } from './support';

// The backend seeds a single demo bill (THB 100) that can only be paid once per
// backend process, so these tests run in order and expect a freshly started API.
test.describe.configure({ mode: 'serial' });

test('reports an unknown bill without charging the account', async ({ page, request }, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await openAccount(request, accountId, 500);

  await page.goto(`/accounts/${accountId}/bill-payment`);
  await page.getByLabel('Reference code 2').fill('invoice-does-not-exist');
  await page.getByRole('button', { name: 'Check current amount' }).click();

  await expect(page.getByRole('alert')).toContainText('bill_not_found');
  await expect(page.getByRole('heading', { name: 'Confirm the details' })).toBeHidden();
});

test('rejects a confirmed bill payment when funds are insufficient', async ({
  page,
  request,
}, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await openAccount(request, accountId, 40);

  await page.goto(`/accounts/${accountId}/bill-payment`);
  await page.getByRole('button', { name: 'Check current amount' }).click();
  await expect(page.locator('.bill-amount strong')).toHaveText('฿100.00');
  await page.getByRole('button', { name: 'Confirm payment' }).click();

  await expect(page).toHaveURL(new RegExp(`/accounts/${accountId}/bill-payments/[0-9a-f-]+$`));
  await expect(page.getByRole('heading', { name: 'Your money is safe' })).toBeVisible();

  await page.getByRole('link', { name: 'Return to account' }).click();
  await expect(balanceCard(page)).toHaveText('฿40.00');
  await expect(activityRows(page)).toHaveCount(1);
});

test('pays the demo bill end to end and records the ledger activity', async ({
  page,
  request,
}, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await openAccount(request, accountId, 150);

  await page.goto(`/accounts/${accountId}`);
  await page.getByRole('link', { name: /Pay a bill/ }).click();
  await expect(page.getByLabel('Biller code')).toHaveValue('demo-biller');
  await page.getByRole('button', { name: 'Check current amount' }).click();

  await expect(page.getByRole('heading', { name: 'Confirm the details' })).toBeVisible();
  await expect(page.locator('.bill-amount strong')).toHaveText('฿100.00');
  await page.getByRole('button', { name: 'Confirm payment' }).click();

  await expect(page.getByText('Payment complete')).toBeVisible();
  await expect(page.getByRole('heading', { name: '฿100.00' })).toBeVisible();
  const receipt = page.locator('.receipt-details');
  await expect(receipt).toContainText('Remaining balance');
  await expect(receipt).toContainText('฿50.00');

  await page.getByRole('link', { name: 'Return to account' }).click();
  await expect(balanceCard(page)).toHaveText('฿50.00');
  const billPayment = activityRows(page).filter({ hasText: 'Bill payment' });
  await expect(billPayment).toContainText('−฿100.00');
  await expect(billPayment).toContainText('Balance ฿50.00');
});

test('refuses to pay a bill that has already been paid', async ({ page, request }, testInfo) => {
  const accountId = uniqueAccountId(testInfo);
  await openAccount(request, accountId, 150);

  await page.goto(`/accounts/${accountId}/bill-payment`);
  await page.getByRole('button', { name: 'Check current amount' }).click();

  await expect(page.getByRole('alert')).toContainText('bill_not_payable');
});
