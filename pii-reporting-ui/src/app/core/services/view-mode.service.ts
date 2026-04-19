import { computed, Injectable, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { inject, PLATFORM_ID } from '@angular/core';

/**
 * Tri-mode view: standard UI, GDPR lens, or Swiss nLPD lens.
 */
export type ViewMode = 'standard' | 'gdpr' | 'nlpd';

const ALL_VIEW_MODES: readonly ViewMode[] = ['standard', 'gdpr', 'nlpd'] as const;

/**
 * Global service that owns the active view mode.
 *
 * The mode is persisted in `localStorage` under
 * {@link ViewModeService.STORAGE_KEY} so that user preference survives
 * reloads and navigation.
 */
@Injectable({ providedIn: 'root' })
export class ViewModeService {
  /** localStorage key used to persist the active view mode. */
  static readonly STORAGE_KEY = 'ai-sentinel-view-mode';

  private readonly isBrowser = isPlatformBrowser(inject(PLATFORM_ID));

  /** Current view mode (reactive signal). */
  readonly viewMode = signal<ViewMode>(this.loadInitialMode());

  /** True when user is in the GDPR legal lens. */
  readonly isGdprMode = computed(() => this.viewMode() === 'gdpr');

  /** True when user is in the Swiss nLPD legal lens. */
  readonly isNlpdMode = computed(() => this.viewMode() === 'nlpd');

  /** True when user is in any legal lens (GDPR or nLPD). */
  readonly isLegalMode = computed(() => this.viewMode() !== 'standard');

  /**
   * Update the active view mode and persist it.
   */
  setMode(mode: ViewMode): void {
    this.viewMode.set(mode);
    if (!this.isBrowser) {
      return;
    }
    try {
      localStorage.setItem(ViewModeService.STORAGE_KEY, mode);
    } catch {
      // localStorage can throw in private mode or when storage is disabled.
      // Persisting is best-effort; the in-memory signal still works.
    }
  }

  private loadInitialMode(): ViewMode {
    if (!this.isBrowser) {
      return 'standard';
    }
    try {
      const stored = localStorage.getItem(ViewModeService.STORAGE_KEY);
      if (stored && (ALL_VIEW_MODES as readonly string[]).includes(stored)) {
        return stored as ViewMode;
      }
    } catch {
      // See setMode() comment.
    }
    return 'standard';
  }
}
