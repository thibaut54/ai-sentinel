import { Injectable, inject, signal } from '@angular/core';
import { MessageService } from 'primeng/api';
import { TranslocoService } from '@jsverse/transloco';

export type ErrorCategory = 'transient' | 'configuration' | 'validation' | 'not_found' | 'conflict';

export interface ErrorNotification {
  errorKey: string;
  message: string;
  status: number;
}

export interface ErrorDetails {
  errorKey: string;
  status: number;
  category: ErrorCategory;
}

export function classifyError(status: number): ErrorCategory {
  if (status === 401 || status === 403) return 'configuration';
  if (status === 400) return 'validation';
  if (status === 404) return 'not_found';
  if (status === 409) return 'conflict';
  return 'transient';
}

@Injectable({ providedIn: 'root' })
export class ErrorNotificationService {
  private readonly messageService = inject(MessageService);
  private readonly translocoService = inject(TranslocoService);
  private readonly bannerError = signal<ErrorNotification | null>(null);
  private readonly recentKeys = new Map<string, number>();

  readonly banner = this.bannerError.asReadonly();

  notify(error: ErrorDetails): void {
    const now = Date.now();

    // Prune stale entries to prevent unbounded growth
    for (const [key, ts] of this.recentKeys) {
      if (now - ts >= 30_000) this.recentKeys.delete(key);
    }

    const lastTime = this.recentKeys.get(error.errorKey);
    if (lastTime !== undefined && now - lastTime < 3000) return;
    this.recentKeys.set(error.errorKey, now);

    const message = this.translocoService.translate(toTranslocoKey(error.errorKey));

    if (error.category === 'configuration') {
      this.bannerError.set({ errorKey: error.errorKey, message, status: error.status });
    } else {
      this.messageService.add({
        severity: 'error',
        summary: this.translocoService.translate('common.error'),
        detail: message,
        life: 8000
      });
    }
  }

  clearBanner(): void {
    this.bannerError.set(null);
  }
}

export function toTranslocoKey(errorKey: string): string {
  if (!errorKey.startsWith('error.')) return errorKey;
  const withoutPrefix = errorKey.substring(6);
  const segments = withoutPrefix.split('.');
  const lastSegment = segments.pop() ?? '';
  const camelLast = lastSegment.replaceAll(/_([a-z])/g, (_, c: string) => c.toUpperCase());
  if (segments.length >= 2) {
    const prev = segments.pop()!;
    const merged = prev + camelLast.charAt(0).toUpperCase() + camelLast.substring(1);
    return `errors.${segments.join('.')}.${merged}`;
  }
  if (segments.length === 0) {
    return `errors.${camelLast}`;
  }
  return `errors.${segments.join('.')}.${camelLast}`;
}
