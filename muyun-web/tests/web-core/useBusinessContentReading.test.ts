import { effectScope, nextTick, reactive, type EffectScope } from 'vue';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useBusinessContentReading } from '@/web-core/useBusinessContentReading';
import type { HttpClient, HttpRequestOptions } from '@/web-core/http';
import type { BusinessContentGenerationStatus, BusinessContentReading } from '@muyun/web-contracts';

const scopes: EffectScope[] = [];
const raw = 'M-100 在 80°C 下抽检 12 件，2 件不合格。';
function reading(language = 'en', translated = false): BusinessContentReading {
  return {
    recordVersion: 4,
    language,
    fields: {
      description: {
        originalText: raw,
        text: translated ? 'M-100: 12 tested at 80°C; 2 failed.' : raw,
        source: {
          revisionId: 'source-1',
          sourceKey: 'owned',
          revision: 1,
          text: raw,
          sourceLanguage: 'zh-CN',
          authorId: 'cn',
          createdAt: '2026-10-16T00:00:00Z',
          authoredAt: null,
          revisionAuthorId: 'cn',
          revisionKind: 'ORIGINAL_WRITE',
        },
        actualLanguage: translated ? language : 'zh-CN',
        originalFallback: !translated,
        translation: translated
          ? {
              kind: 'MACHINE',
              revision: 1,
              text: 'M-100: 12 tested at 80°C; 2 failed.',
              authorId: 'us',
              authorName: 'US',
              createdAt: '2026-10-16T00:00:00Z',
              reviewedDraftRevision: null,
              generationReference: 'job-1',
            }
          : null,
      },
    },
  };
}
function status(state: BusinessContentGenerationStatus['state']): BusinessContentGenerationStatus {
  return { state, reference: 'job-1', attempts: 1, retryAt: null, problem: null };
}
function fixture(request: ReturnType<typeof vi.fn>, id = 'child/one') {
  const options = reactive({
    active: true,
    http: { request } as unknown as HttpClient,
    moduleAlias: 'quality.exception',
    record: { id, version: 4, description: raw, other: 'initial' },
    fieldName: 'description',
    content: { key: 'description', policy: 'AUTO_TRANSLATE' as const },
    language: 'en',
    mode: 'view' as 'view' | 'edit' | 'create',
    relation: { parentId: 'parent/one', relationCode: 'inspections' },
    sessionKey: 1,
  });
  const scope = effectScope();
  scopes.push(scope);
  const view = scope.run(() => useBusinessContentReading(() => options))!;
  return { options, view, scope };
}
async function settle() {
  await nextTick();
  for (let index = 0; index < 12; index++) await Promise.resolve();
}
function generationRequests(request: ReturnType<typeof vi.fn>) {
  return request.mock.calls
    .map((call) => call[0] as HttpRequestOptions)
    .filter((row) => row.path.endsWith('/generation'));
}
beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop());
  vi.useRealTimers();
});

it('stops retained closed surfaces without losing the business draft and resumes only when opened', async () => {
  const request = vi.fn(async (entry: HttpRequestOptions) =>
    entry.path.endsWith('/reading') ? reading(String(entry.query?.language ?? 'en')) : status('PENDING'),
  );
  const { options, view } = fixture(request);
  await settle();
  expect(generationRequests(request)).toHaveLength(1);
  const signal = generationRequests(request)[0].signal as AbortSignal;
  const draft = JSON.stringify(options.record);
  options.active = false;
  await settle();
  expect(signal.aborted).toBe(true);
  expect(view.reading.value).toBeUndefined();
  const calls = request.mock.calls.length;
  options.language = 'de';
  await settle();
  await vi.advanceTimersByTimeAsync(20_000);
  expect(request).toHaveBeenCalledTimes(calls);
  expect(JSON.stringify(options.record)).toBe(draft);
  options.active = true;
  await settle();
  expect(generationRequests(request)).toHaveLength(2);
});

it('offers explicit recovery for a persisted unavailable task after routing becomes available again', async () => {
  const request = vi.fn(async (entry: HttpRequestOptions) =>
    entry.path.endsWith('/reading') ? reading() : status('UNAVAILABLE'),
  );
  const { view } = fixture(request);
  await settle();
  expect(view.canRetry.value).toBe(true);
  expect(generationRequests(request)).toHaveLength(1);
  expect(generationRequests(request)[0]).toMatchObject({ method: 'POST', query: { retry: false } });
  await vi.advanceTimersByTimeAsync(20_000);
  expect(generationRequests(request)).toHaveLength(1);
  await view.retry();
  expect(generationRequests(request).at(-1)).toMatchObject({ method: 'POST', query: { retry: true } });
});

it('requests a missing saved child translation without source text and refreshes only after READY', async () => {
  let translated = false;
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/reading')) return reading('en', translated);
    if (options.method === 'POST') return status('PENDING');
    translated = true;
    return status('READY');
  });
  const { options, view } = fixture(request);
  const baseline = JSON.stringify(options.record);
  await settle();
  expect(view.reading.value?.text).toBe(raw);
  expect(generationRequests(request)[0]).toMatchObject({
    path: '/quality.exception/view/parent%2Fone/relations/inspections/child%2Fone/content/description/translations/en/generation',
    method: 'POST',
    query: { retry: false },
  });
  expect(generationRequests(request)[0]).not.toHaveProperty('body');
  await vi.advanceTimersByTimeAsync(2_000);
  await settle();
  expect(generationRequests(request)).toHaveLength(2);
  expect(generationRequests(request)[1]).not.toHaveProperty('method');
  expect(view.reading.value?.translation?.kind).toBe('MACHINE');
  expect(view.generationBusy.value).toBe(false);
  expect(JSON.stringify(options.record)).toBe(baseline);
  expect(vi.getTimerCount()).toBe(0);
});

it.each(['UNAVAILABLE', 'REMOVED', 'UNSUPPORTED', 'FAILED', 'CANCELLED', 'STALE'] as const)(
  'does not poll or automatically replay terminal %s',
  async (state) => {
    const request = vi.fn(async (options: HttpRequestOptions) =>
      options.path.endsWith('/reading') ? reading() : status(state),
    );
    const { view } = fixture(request);
    await settle();
    await vi.advanceTimersByTimeAsync(300_000);
    expect(generationRequests(request)).toHaveLength(1);
    expect(view.status.value?.state).toBe(state);
    expect(view.reading.value?.originalText).toBe(raw);
    expect(view.canRetry.value).toBe(['FAILED', 'CANCELLED', 'STALE', 'UNAVAILABLE'].includes(state));
  },
);

it('only explicit retry renews a terminal request and preserves its original', async () => {
  const request = vi.fn(async (options: HttpRequestOptions) =>
    options.path.endsWith('/reading') ? reading() : status(options.query?.retry ? 'PENDING' : 'FAILED'),
  );
  const { view, options } = fixture(request);
  await settle();
  await view.retry();
  expect(generationRequests(request).map((row) => row.query?.retry)).toEqual([false, true]);
  expect(options.record.description).toBe(raw);
  expect(view.generationBusy.value).toBe(true);
});

it('bounds status polling without adding new POST requests and stops on disposal', async () => {
  const request = vi.fn(async (options: HttpRequestOptions) =>
    options.path.endsWith('/reading') ? reading() : status('RUNNING'),
  );
  const { view, scope } = fixture(request);
  await settle();
  await vi.advanceTimersByTimeAsync(122_000);
  expect(generationRequests(request).filter((row) => row.method === 'POST')).toHaveLength(1);
  expect(generationRequests(request)).toHaveLength(61);
  expect(view.pollingPaused.value).toBe(true);
  expect(view.generationBusy.value).toBe(false);
  await view.load();
  await settle();
  const signal = generationRequests(request).at(-1)!.signal!;
  scope.stop();
  expect(signal.aborted).toBe(true);
  const count = request.mock.calls.length;
  await vi.advanceTimersByTimeAsync(10_000);
  expect(request).toHaveBeenCalledTimes(count);
});

it('stops polling when the raw business draft changes without reading or writing per keystroke', async () => {
  const request = vi.fn(async (options: HttpRequestOptions) =>
    options.path.endsWith('/reading') ? reading() : status('RUNNING'),
  );
  const { view, options } = fixture(request);
  await settle();
  const count = request.mock.calls.length;
  options.record.description = 'Unsaved English business edit';
  await settle();
  await vi.advanceTimersByTimeAsync(10_000);
  expect(request).toHaveBeenCalledTimes(count);
  expect(view.matches.value).toBe(false);
  expect(view.status.value).toBeUndefined();
  expect(options.record.description).toBe('Unsaved English business edit');
  options.language = 'de';
  await settle();
  expect(options.record.description).toBe('Unsaved English business edit');
  expect(generationRequests(request)).toHaveLength(1);
});

it('ignores a delayed old-language request and never applies it to a replacement session', async () => {
  let old!: (value: BusinessContentGenerationStatus) => void;
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/reading')) return reading(String(options.query?.language));
    if (options.path.includes('/en/'))
      return new Promise((resolve) => {
        old = resolve;
      });
    return status('UNAVAILABLE');
  });
  const { view, options } = fixture(request);
  await settle();
  const signal = generationRequests(request)[0]!.signal!;
  options.language = 'de';
  options.sessionKey = 2;
  await settle();
  expect(signal.aborted).toBe(true);
  old(status('READY'));
  await settle();
  expect(view.status.value?.state).toBe('UNAVAILABLE');
  expect(view.reading.value?.actualLanguage).toBe('zh-CN');
  expect(
    request.mock.calls.filter((call) => (call[0] as HttpRequestOptions).query?.language === 'en'),
  ).toHaveLength(1);
});

it('keeps raw reading available after a denied status call and offers only explicit recovery', async () => {
  const request = vi.fn(async (options: HttpRequestOptions) => {
    if (options.path.endsWith('/reading')) return reading();
    if (options.method === 'POST') return status('PENDING');
    throw new Error('scope withdrawn');
  });
  const { view } = fixture(request);
  await settle();
  await vi.advanceTimersByTimeAsync(2_000);
  expect(view.generationFailed.value).toBe(true);
  expect(view.canRetry.value).toBe(true);
  expect(view.reading.value?.text).toBe(raw);
  expect(vi.getTimerCount()).toBe(0);
});

it('does not generate for an existing translation, unknown source or an unsaved create', async () => {
  const request = vi.fn(async () => reading('en', true));
  const { view, options } = fixture(request);
  await settle();
  expect(generationRequests(request)).toHaveLength(0);
  request.mockImplementation(async () => {
    const result = reading();
    result.fields.description!.source!.sourceLanguage = null;
    return result;
  });
  await view.load();
  await settle();
  expect(generationRequests(request)).toHaveLength(0);
  options.mode = 'create';
  await settle();
  expect(view.reading.value).toBeUndefined();
  expect(generationRequests(request)).toHaveLength(0);
});

it('does not restart reading or generation when immutable form/detail snapshots are rebuilt', async () => {
  const request = vi.fn(async (options: HttpRequestOptions) =>
    options.path.endsWith('/reading') ? reading() : status('RUNNING'),
  );
  const first = fixture(request, 'line-one');
  const second = fixture(request, 'line-two');
  await settle();
  expect(generationRequests(request)).toHaveLength(2);
  const signals = generationRequests(request).map((row) => row.signal!);
  const count = request.mock.calls.length;
  first.options.record = { ...first.options.record, other: 'user typing into an unrelated field' };
  second.options.record = { ...second.options.record };
  await settle();
  expect(request).toHaveBeenCalledTimes(count);
  expect(signals.every((signal) => !signal.aborted)).toBe(true);
  expect(first.view.reading.value?.text).toBe(raw);
  expect(second.view.reading.value?.text).toBe(raw);
  first.options.record = { ...first.options.record, description: 'Unsaved immutable business edit' };
  second.options.record = { ...second.options.record };
  await settle();
  expect(request).toHaveBeenCalledTimes(count);
  expect(signals[0]!.aborted).toBe(true);
  expect(signals[1]!.aborted).toBe(false);
  expect(first.view.matches.value).toBe(false);
  await vi.advanceTimersByTimeAsync(2_000);
  expect(generationRequests(request).filter((row) => row.method === 'POST')).toHaveLength(2);
  expect(generationRequests(request).at(-1)!.path).toContain('/line-two/');
});

it('starts a separate reading when only the page session changes and ignores the old session response', async () => {
  const callbacks: ((value: BusinessContentReading) => void)[] = [];
  const request = vi.fn(async (options: HttpRequestOptions) =>
    options.path.endsWith('/reading')
      ? new Promise<BusinessContentReading>((resolve) => callbacks.push(resolve))
      : status('UNAVAILABLE'),
  );
  const { options, view } = fixture(request);
  await settle();
  const sameRecord = options.record;
  options.sessionKey = 2;
  await settle();
  expect(options.record).toBe(sameRecord);
  expect(callbacks).toHaveLength(2);
  callbacks[0]!(reading('en', true));
  await settle();
  expect(view.reading.value).toBeUndefined();
  expect(generationRequests(request)).toHaveLength(0);
  callbacks[1]!(reading());
  await settle();
  expect(view.reading.value?.originalFallback).toBe(true);
  expect(view.status.value?.state).toBe('UNAVAILABLE');
  expect(generationRequests(request)).toHaveLength(1);
});
