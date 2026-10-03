import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () =>
      import('./features/account-entry/account-entry').then((module) => module.AccountEntry),
  },
  {
    path: 'accounts/:accountId',
    loadComponent: () =>
      import('./features/account-dashboard/account-dashboard').then(
        (module) => module.AccountDashboard,
      ),
  },
  {
    path: 'accounts/:accountId/deposit',
    data: { operation: 'deposit' },
    loadComponent: () =>
      import('./features/money-operation/money-operation').then(
        (module) => module.MoneyOperation,
      ),
  },
  {
    path: 'accounts/:accountId/withdraw',
    data: { operation: 'withdraw' },
    loadComponent: () =>
      import('./features/money-operation/money-operation').then(
        (module) => module.MoneyOperation,
      ),
  },
  {
    path: 'accounts/:accountId/bill-payment',
    loadComponent: () =>
      import('./features/bill-payment/bill-payment').then((module) => module.BillPayment),
  },
  {
    path: 'accounts/:accountId/bill-payments/:paymentId',
    loadComponent: () =>
      import('./features/payment-status/payment-status').then((module) => module.PaymentStatus),
  },
  { path: '**', redirectTo: '' },
];
