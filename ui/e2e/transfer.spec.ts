import { expect, test } from '@playwright/test';
import { activityRows, balanceCard, openAccount, uniqueAccountId } from './support';

test('transfers funds and records linked activity for both accounts', async ({
  page,
  request,
}, testInfo) => {
  const sourceAccountId = `${uniqueAccountId(testInfo)}-source`;
  const destinationAccountId = `${uniqueAccountId(testInfo)}-destination`;
  await openAccount(request, sourceAccountId, 100);
  await openAccount(request, destinationAccountId);

  await page.goto(`/accounts/${sourceAccountId}`);
  await page.getByRole('link', { name: /Transfer/ }).click();
  await expect(page).toHaveURL(`/accounts/${sourceAccountId}/transfer`);
  await page.getByLabel('Destination account').fill(destinationAccountId);
  await page.getByLabel('Amount').fill('35.25');
  await page.getByRole('button', { name: 'Confirm transfer' }).click();

  await expect(page.getByRole('heading', { name: 'Transfer complete' })).toBeVisible();
  await expect(page.locator('.success-state')).toContainText(destinationAccountId);
  await expect(page.locator('.success-state')).toContainText('฿64.75');

  await page.getByRole('link', { name: 'Return to account' }).click();
  await expect(balanceCard(page)).toHaveText('฿64.75');
  const outgoing = activityRows(page).filter({ hasText: 'Transfer sent' });
  await expect(outgoing).toContainText(destinationAccountId);
  await expect(outgoing).toContainText('−฿35.25');

  await page.goto(`/accounts/${destinationAccountId}`);
  await expect(balanceCard(page)).toHaveText('฿35.25');
  const incoming = activityRows(page).filter({ hasText: 'Transfer received' });
  await expect(incoming).toContainText(sourceAccountId);
  await expect(incoming).toContainText('+฿35.25');
});
