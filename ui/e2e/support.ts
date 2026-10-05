import { APIRequestContext, expect, Page, TestInfo } from '@playwright/test';

export function uniqueAccountId(testInfo: TestInfo): string {
  return `e2e-${Date.now()}-${testInfo.workerIndex}-${testInfo.retry}`;
}

export async function openAccount(
  request: APIRequestContext,
  accountId: string,
  initialDeposit?: number,
): Promise<void> {
  const created = await request.put(`/api/accounts/${accountId}`, { data: {} });
  expect(created.ok()).toBe(true);

  if (initialDeposit !== undefined) {
    const deposited = await request.post(`/api/accounts/${accountId}/deposits`, {
      data: { amount: initialDeposit },
    });
    expect(deposited.ok()).toBe(true);
  }
}

export function balanceCard(page: Page) {
  return page.locator('.balance-card strong');
}

export function activityRows(page: Page) {
  return page.locator('.activity-row');
}
