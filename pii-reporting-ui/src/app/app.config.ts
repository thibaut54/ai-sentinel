import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection
} from '@angular/core';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { providePrimeNG } from 'primeng/config';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { errorInterceptor } from './core/interceptors/error.interceptor';
import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

// Align the PrimeNG primary accent with the blue used in the obfuscation view
// (blue-600 = #2563eb) instead of Aura's default green.
const SentinelPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{blue.50}',
      100: '{blue.100}',
      200: '{blue.200}',
      300: '{blue.300}',
      400: '{blue.400}',
      500: '{blue.500}',
      600: '{blue.600}',
      700: '{blue.700}',
      800: '{blue.800}',
      900: '{blue.900}',
      950: '{blue.950}'
    }
  }
});
import { ConfluenceSpacesPollingService } from './core/services/confluence-spaces-polling.service';
import { provideTransloco } from '@jsverse/transloco';
import { TranslocoHttpLoader } from './core/services/transloco-http-loader';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ToastService } from './core/services/toast.service';
import { SentinelleApiService } from './core/services/sentinelle-api.service';
import { RemediationConfigService } from './core/services/remediation-config.service';

export const appConfig: ApplicationConfig = {
  providers: [
    ConfirmationService,
    MessageService,
    ToastService,
    providePrimeNG({
      theme: {
        preset: SentinelPreset,
        options: {
          darkModeSelector: '[data-theme="dark"]'
        }
      }
    }),
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideHttpClient(withInterceptors([errorInterceptor])),
    provideRouter(routes),
    provideTransloco({
      config: {
        availableLangs: ['fr', 'en'],
        defaultLang: 'fr',
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
        fallbackLang: 'fr',
        missingHandler: {
          useFallbackTranslation: true,
          logMissingKey: !isDevMode()
        }
      },
      loader: TranslocoHttpLoader
    }),
    provideAppInitializer(() => inject(ConfluenceSpacesPollingService).loadPollingConfig()),
    provideAppInitializer(() => inject(SentinelleApiService).loadRevealConfig()),
    provideAppInitializer(() => inject(RemediationConfigService).loadRemediationConfig())
  ]
};
