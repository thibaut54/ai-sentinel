import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Subscription } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { SentinelleApiService } from './sentinelle-api.service';
import { StreamEvent } from '../models/stream-event';

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  readonly url: string;
  onerror: ((this: EventSource, ev: Event) => unknown) | null = null;
  closed = false;
  private readonly listeners = new Map<string, EventListener>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: EventListener): void {
    this.listeners.set(type, listener);
  }

  emit(type: string, data: string): void {
    const listener = this.listeners.get(type);
    if (listener) {
      listener({ data } as MessageEvent as unknown as Event);
    }
  }

  triggerError(): void {
    this.onerror?.call(this as unknown as EventSource, new Event('error'));
  }

  close(): void {
    this.closed = true;
  }
}

describe('SentinelleApiService SSE streams', () => {
  let service: SentinelleApiService;
  const originalEventSource = (globalThis as { EventSource?: unknown }).EventSource;

  beforeEach(() => {
    FakeEventSource.instances = [];
    (globalThis as { EventSource?: unknown }).EventSource = FakeEventSource as unknown as typeof EventSource;

    TestBed.configureTestingModule({
      providers: [
        SentinelleApiService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(SentinelleApiService);
  });

  afterEach(() => {
    (globalThis as { EventSource?: unknown }).EventSource = originalEventSource;
  });

  it('Should_OpenEventSourceWithoutScanId_When_StartAllSpacesStreamWithoutId', () => {
    const subscription = service.startAllSpacesStream().subscribe();

    expect(FakeEventSource.instances[0].url).toBe('/api/v1/stream/confluence/spaces/events');
    subscription.unsubscribe();
  });

  it('Should_AppendScanIdQuery_When_StartAllSpacesStreamWithId', () => {
    const subscription = service.startAllSpacesStream('scan 1').subscribe();

    expect(FakeEventSource.instances[0].url).toBe('/api/v1/stream/confluence/spaces/events?scanId=scan%201');
    subscription.unsubscribe();
  });

  it('Should_EmitParsedEvent_When_SseMessageReceived', () => {
    const received: StreamEvent[] = [];
    const subscription = service.startAllSpacesStream().subscribe((event) => received.push(event));

    FakeEventSource.instances[0].emit('item', JSON.stringify({ scanId: 'scan-1', pageId: 1 }));

    expect(received).toHaveLength(1);
    expect(received[0].type).toBe('item');
    expect(received[0].data).toEqual({ scanId: 'scan-1', pageId: 1 });
    subscription.unsubscribe();
  });

  it('Should_EmitUndefinedData_When_SsePayloadIsInvalidJson', () => {
    const received: StreamEvent[] = [];
    const subscription = service.startAllSpacesStream().subscribe((event) => received.push(event));

    FakeEventSource.instances[0].emit('keepalive', 'not-json');

    expect(received).toHaveLength(1);
    expect(received[0].data).toBeUndefined();
    subscription.unsubscribe();
  });

  it('Should_ErrorObservable_When_EventSourceErrors', () => {
    let error: Error | undefined;
    const subscription = service.startAllSpacesStream().subscribe({ error: (err: Error) => (error = err) });

    FakeEventSource.instances[0].triggerError();

    expect(error?.message).toBe('SSE connection error');
    subscription.unsubscribe();
  });

  it('Should_CloseEventSource_When_Unsubscribed', () => {
    const subscription: Subscription = service.startAllSpacesStream().subscribe();
    const instance = FakeEventSource.instances[0];

    subscription.unsubscribe();

    expect(instance.closed).toBe(true);
  });

  it('Should_BuildSelectedKeysQuery_When_StartSelectedSpacesStream', () => {
    const subscription = service.startSelectedSpacesStream(['A', 'B C']).subscribe();

    expect(FakeEventSource.instances[0].url).toBe(
      '/api/v1/stream/confluence/spaces/events/selected?spaceKeys=A&spaceKeys=B%20C'
    );
    subscription.unsubscribe();
  });

  it('Should_EmitParsedEvent_When_SelectedStreamMessageReceived', () => {
    const received: StreamEvent[] = [];
    const subscription = service.startSelectedSpacesStream(['A']).subscribe((event) => received.push(event));

    FakeEventSource.instances[0].emit('complete', JSON.stringify({ status: 'COMPLETED' }));

    expect(received[0].type).toBe('complete');
    expect(received[0].data).toEqual({ status: 'COMPLETED' });
    subscription.unsubscribe();
  });

  it('Should_ErrorObservable_When_SelectedStreamEventSourceErrors', () => {
    let error: Error | undefined;
    const subscription = service.startSelectedSpacesStream(['A']).subscribe({ error: (err: Error) => (error = err) });

    FakeEventSource.instances[0].triggerError();

    expect(error?.message).toBe('SSE connection error');
    subscription.unsubscribe();
  });
});
