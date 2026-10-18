import { effectScope, nextTick, reactive, type EffectScope } from 'vue';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useBusinessContentGenerationBatch } from '@/web-core/useBusinessContentGenerationBatch';
import type { HttpClient, HttpRequestOptions } from '@/web-core/http';
import type {
  BusinessContentGenerationBatchResult,
  BusinessContentGenerationSource,
  BusinessContentGenerationStatus,
} from '@muyun/web-contracts';

const scopes: EffectScope[] = [];
function response(
  state: BusinessContentGenerationStatus['state'],
  language = 'de',
  reference: string | null = 'job',
): BusinessContentGenerationBatchResult {
  return {
    language,
    items: [
      {
        recordId: 'row',
        ownerId: 'parent',
        relationCode: 'lines',
        childId: 'row',
        field: 'description',
        status: { state, reference, problem: null, attempts: 0, retryAt: null },
      },
    ],
  };
}
function fixture(request: ReturnType<typeof vi.fn>, active = true) {
  const options = reactive({
    active,
    http: { request } as unknown as HttpClient,
    source: { moduleAlias: 'quality.exception', parentId: 'parent', relationCode: 'lines' } as
      | BusinessContentGenerationSource
      | undefined,
    records: [{ id: 'row', version: 1 }],
    language: 'de',
    includeChildren: false,
    sessionKey: 1,
  });
  const scope = effectScope();
  scopes.push(scope);
  const view = scope.run(() => useBusinessContentGenerationBatch(() => options))!;
  return { options, view, scope };
}
async function settle() {
  await nextTick();
  for (let i = 0; i < 12; i++) await Promise.resolve();
}
beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop());
  vi.useRealTimers();
});

it('previews without generating, then polls only status after the user explicitly requests the selected language', async () => {
  let state: BusinessContentGenerationStatus['state'] = 'MISSING';
  const request = vi.fn(async (entry: HttpRequestOptions) => {
    if (!entry.path.endsWith('/status')) {
      state = 'PENDING';
      return response(state);
    }
    if (state === 'PENDING') state = 'READY';
    return response(state);
  });
  const { options, view } = fixture(request);
  await settle();
  expect(request).toHaveBeenCalledTimes(1);
  expect(request.mock.calls[0][0]).toMatchObject({
    method: 'POST',
    path: '/quality.exception/view/parent/relations/lines/content/generation/status',
    body: { ids: ['row'], language: 'de', retry: false, includeChildren: false },
  });
  expect(view.canGenerate.value).toBe(true);
  const baseline = JSON.stringify(options.records);
  await view.request();
  expect(view.pending.value).toBe(true);
  await vi.advanceTimersByTimeAsync(2_000);
  await settle();
  expect(view.items.value[0]?.status.state).toBe('READY');
  expect(request.mock.calls.filter(([entry]) => !entry.path.endsWith('/status'))).toHaveLength(1);
  expect(JSON.stringify(options.records)).toBe(baseline);
  expect(request.mock.calls[1][0].body).toEqual({
    ids: ['row'],
    language: 'de',
    includeChildren: false,
    retry: false,
  });
});

it('withdraws a late selection result when the owner, source version, language or authenticated session changes', async () => {
  let finish!: (value: BusinessContentGenerationBatchResult) => void;
  const request = vi
    .fn()
    .mockImplementationOnce(
      () =>
        new Promise((done) => {
          finish = done;
        }),
    )
    .mockResolvedValue(response('READY', 'en'));
  const { options, view } = fixture(request);
  const firstSignal = request.mock.calls[0][0].signal as AbortSignal;
  options.source = { moduleAlias: 'quality.exception', parentId: 'new-parent', relationCode: 'lines' };
  options.records = [{ id: 'new-row', version: 2 }];
  options.language = 'en';
  options.sessionKey = 2;
  await settle();
  expect(firstSignal.aborted).toBe(true);
  finish(response('FAILED'));
  await settle();
  expect(view.result.value?.language).toBe('en');
  expect(view.failed.value).toBe(false);
  expect(request.mock.calls.at(-1)?.[0]).toMatchObject({
    path: '/quality.exception/view/new-parent/relations/lines/content/generation/status',
    body: { ids: ['new-row'], language: 'en' },
  });
  expect(request.mock.calls.every(([entry]) => entry.path.endsWith('/status'))).toBe(true);
});

it('bounds status polling and never turns polling into generation or an automatic retry', async () => {
  const request = vi.fn().mockResolvedValue(response('RUNNING'));
  const { view } = fixture(request);
  await settle();
  await vi.advanceTimersByTimeAsync(124_000);
  await settle();
  expect(view.pollingPaused.value).toBe(true);
  expect(request).toHaveBeenCalledTimes(61);
  expect(request.mock.calls.every(([entry]) => entry.path.endsWith('/status'))).toBe(true);
  await vi.advanceTimersByTimeAsync(60_000);
  expect(request).toHaveBeenCalledTimes(61);
  await view.preview();
  expect(request).toHaveBeenCalledTimes(62);
});

it('keeps an unavailable route idle and requires explicit retry to recover a persisted unavailable job', async () => {
  const request = vi.fn().mockResolvedValue(response('UNAVAILABLE', 'de', null));
  const { view } = fixture(request);
  await settle();
  expect(view.canGenerate.value).toBe(true);
  expect(view.canRetry.value).toBe(false);
  await vi.advanceTimersByTimeAsync(20_000);
  expect(request).toHaveBeenCalledTimes(1);
  request.mockResolvedValue(response('UNAVAILABLE'));
  await view.preview();
  expect(view.canGenerate.value).toBe(false);
  expect(view.canRetry.value).toBe(true);
  request.mockResolvedValue(response('PENDING'));
  await view.request(true);
  expect(request.mock.calls.at(-1)?.[0]).toMatchObject({
    path: '/quality.exception/view/parent/relations/lines/content/generation',
    body: { retry: true },
  });
});

it('does not read or generate an inactive, unaddressed, empty or excessive selection and aborts when disposed', async () => {
  const request = vi.fn().mockResolvedValue(response('MISSING'));
  const { options, view, scope } = fixture(request, false);
  await settle();
  expect(request).not.toHaveBeenCalled();
  options.records = [];
  options.active = true;
  await settle();
  expect(request).not.toHaveBeenCalled();
  options.records = Array.from({ length: 51 }, (_, id) => ({ id: String(id), version: 1 }));
  await settle();
  expect(request).not.toHaveBeenCalled();
  options.source = undefined;
  options.records = [{ id: 'row', version: 1 }];
  await settle();
  expect(request).not.toHaveBeenCalled();
  options.source = { moduleAlias: 'quality.exception' };
  await settle();
  expect(request).toHaveBeenCalledTimes(1);
  const signal = request.mock.calls[0][0].signal as AbortSignal;
  scope.stop();
  expect(signal.aborted).toBe(true);
  expect(view.result.value).toBeUndefined();
  await vi.advanceTimersByTimeAsync(20_000);
  expect(request).toHaveBeenCalledTimes(1);
});
