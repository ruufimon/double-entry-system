import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'accounts' },
  {
    path: 'accounts',
    loadComponent: () =>
      import('./features/account-list/account-list').then((module) => module.AccountList),
  },
  {
    path: 'accounts/:accountId',
    loadComponent: () =>
      import('./features/account-detail/account-detail').then(
        (module) => module.AccountDetail,
      ),
  },
  {
    path: 'interest-rate',
    loadComponent: () =>
      import('./features/interest-rate/interest-rate').then((module) => module.InterestRate),
  },
  { path: '**', redirectTo: 'accounts' },
];
