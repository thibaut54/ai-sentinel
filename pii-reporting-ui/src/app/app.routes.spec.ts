import { routes } from './app.routes';
import { PiiObfuscationComponent } from './features/pii-obfuscation/pii-obfuscation.component';

describe('appRoutes', () => {
  it('Should_RegisterObfuscationRoute_When_AppConfigured', () => {
    const route = routes.find((r) => r.path === 'obfuscation');

    expect(route?.component).toBe(PiiObfuscationComponent);
  });

  it('Should_KeepWildcardRedirectLast_When_AppConfigured', () => {
    expect(routes[routes.length - 1].path).toBe('**');
  });
});
