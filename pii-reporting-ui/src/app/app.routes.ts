import { Routes } from '@angular/router';
import { AppShellComponent } from './features/app-shell/app-shell.component';
import { PiiSettingsComponent } from './features/pii-settings/pii-settings.component';

export const routes: Routes = [
  {
    path: '',
    component: AppShellComponent,
    children: [
      { path: '', redirectTo: 'confluence', pathMatch: 'full' },
      {
        path: 'confluence',
        loadComponent: () =>
          import('./features/confluence-dashboard/confluence-dashboard.component').then(
            (m) => m.ConfluenceDashboardComponent
          ),
      },
      {
        path: 'jira',
        loadComponent: () =>
          import('./features/jira-dashboard/jira-dashboard.component').then(
            (m) => m.JiraDashboardComponent
          ),
      },
      {
        path: 'sharepoint',
        loadComponent: () =>
          import('./features/sharepoint-dashboard/sharepoint-dashboard.component').then(
            (m) => m.SharePointDashboardComponent
          ),
      },
    ],
  },
  { path: 'settings', component: PiiSettingsComponent },
  { path: '**', redirectTo: '' },
];
