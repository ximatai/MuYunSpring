import { expect, it, vi } from 'vitest';
import {
  businessContentPath,
  loadBusinessContentSources,
  loadBusinessContentHistory,
} from '@/web-core/businessContent';
import type { BusinessContentSources } from '@muyun/web-contracts';

it('uses the actual owned row path and preserves the context transport', async () => {
  const request = vi.fn().mockResolvedValue([]);
  const http = { request };
  const relation = { parentId: 'parent/1', relationCode: 'lines' };
  expect(businessContentPath('demo.issue', 'child/2', relation)).toBe(
    '/demo.issue/view/parent%2F1/relations/lines/child%2F2/content',
  );
  await loadBusinessContentHistory(http, 'demo.issue', 'child/2', 'description', 2, relation);
  expect(request).toHaveBeenCalledWith({
    path: '/demo.issue/view/parent%2F1/relations/lines/child%2F2/content/description/history',
    query: { page: 2, size: 20 },
  });
});

it('shares only an explicit live draft snapshot and never retains a completed permission read', async () => {
  let resolve!: (value: BusinessContentSources) => void;
  const request = vi
    .fn()
    .mockImplementationOnce(() => new Promise<BusinessContentSources>((done) => (resolve = done)))
    .mockResolvedValue({ recordVersion: 1, fields: {} });
  const http = { request };
  const first = {};
  const a = loadBusinessContentSources(http, 'demo.issue', 'row', undefined, first);
  const b = loadBusinessContentSources(http, 'demo.issue', 'row', undefined, first);
  expect(a).toBe(b);
  const other = loadBusinessContentSources(http, 'demo.issue', 'row', undefined, {});
  expect(other).not.toBe(a);
  expect(request).toHaveBeenCalledTimes(2);
  await other;
  resolve({ recordVersion: 1, fields: {} });
  await a;
  await loadBusinessContentSources(http, 'demo.issue', 'row', undefined, first);
  expect(request).toHaveBeenCalledTimes(3);
});

it('starts a new read when the same draft object advances to a newer business version', async () => {
  const done: ((value: BusinessContentSources) => void)[] = [];
  const request = vi
    .fn()
    .mockImplementation(() => new Promise<BusinessContentSources>((resolve) => done.push(resolve)));
  const http = { request };
  const snapshot = { version: 4 };
  const old = loadBusinessContentSources(http, 'demo.issue', 'row', undefined, snapshot);
  snapshot.version = 5;
  const current = loadBusinessContentSources(http, 'demo.issue', 'row', undefined, snapshot);
  expect(request).toHaveBeenCalledTimes(2);
  done[0]!({ recordVersion: 4, fields: {} });
  await old;
  expect(loadBusinessContentSources(http, 'demo.issue', 'row', undefined, snapshot)).toBe(current);
  done[1]!({ recordVersion: 5, fields: {} });
  expect((await current).recordVersion).toBe(5);
});

it('shares only in-flight reading for the same snapshot, language, version and transport', async () => {
  const { loadBusinessContentReading } = await import('@/web-core/businessContent');
  const callbacks: ((value: unknown) => void)[] = [];
  const request = vi.fn(() => new Promise((done) => callbacks.push(done)));
  const http = { request } as unknown as import('@/web-core/http').HttpClient;
  const snapshot = { version: 1 };
  const first = loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot);
  expect(loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot)).toBe(first);
  const german = loadBusinessContentReading(http, 'demo.issue', 'one', 'de', undefined, snapshot);
  snapshot.version = 2;
  const next = loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot);
  callbacks[0]!({});
  await first;
  expect(loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot)).toBe(next);
  callbacks[1]!({});
  callbacks[2]!({});
  await Promise.all([german, next]);
  const fresh = loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot);
  expect(request).toHaveBeenCalledTimes(4);
  callbacks[3]!({});
  await fresh;
});

it('invalidates a pending reading after a successful translation write while retaining the business version', async () => {
  const { loadBusinessContentReading, changeBusinessContentTranslation } =
    await import('@/web-core/businessContent');
  const callbacks: ((value: unknown) => void)[] = [];
  const request = vi.fn((options: import('@/web-core/http').HttpRequestOptions) =>
    options.method === 'POST'
      ? Promise.resolve({ expectedRevision: 4 })
      : new Promise((done) => callbacks.push(done)),
  );
  const http = { request } as unknown as import('@/web-core/http').HttpClient;
  const snapshot = { version: 1 };
  const old = loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot);
  await changeBusinessContentTranslation(
    http,
    'demo.issue',
    'one',
    'description',
    'en',
    'confirm',
    {
      sourceRevisionId: 'immutable-original',
      sourceRevision: 1,
      expectedRevision: 3,
      reviewedDraftRevision: 3,
    },
    undefined,
    snapshot,
  );
  const fresh = loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot);
  expect(fresh).not.toBe(old);
  callbacks[0]!({});
  await old;
  expect(loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot)).toBe(fresh);
  callbacks[1]!({});
  await fresh;
  expect(snapshot.version).toBe(1);
});

it('keeps generation status read-only and refreshes an in-flight projection only when generation is ready', async () => {
  const { loadBusinessContentReading, loadBusinessContentGeneration, requestBusinessContentGeneration } =
    await import('@/web-core/businessContent');
  const callbacks: ((value: unknown) => void)[] = [];
  const request = vi.fn((options: import('@/web-core/http').HttpRequestOptions) =>
    options.path.endsWith('/generation')
      ? Promise.resolve({ state: 'READY' })
      : new Promise((resolve) => callbacks.push(resolve)),
  );
  const http = { request } as unknown as import('@/web-core/http').HttpClient;
  const snapshot = { version: 4 };
  const options = { snapshot, relation: { parentId: 'parent/one', relationCode: 'lines' } };
  const old = loadBusinessContentReading(
    http,
    'quality.exception',
    'child/one',
    'de',
    options.relation,
    snapshot,
  );
  await loadBusinessContentGeneration(http, 'quality.exception', 'child/one', 'description', 'de', options);
  expect(request.mock.calls[1]![0]).toEqual({
    path: '/quality.exception/view/parent%2Fone/relations/lines/child%2Fone/content/description/translations/de/generation',
  });
  const fresh = loadBusinessContentReading(
    http,
    'quality.exception',
    'child/one',
    'de',
    options.relation,
    snapshot,
  );
  expect(fresh).not.toBe(old);
  callbacks[0]!({});
  await old;
  expect(
    loadBusinessContentReading(http, 'quality.exception', 'child/one', 'de', options.relation, snapshot),
  ).toBe(fresh);
  callbacks[1]!({});
  await fresh;
  await requestBusinessContentGeneration(
    http,
    'quality.exception',
    'child/one',
    'description',
    'de',
    true,
    options,
  );
  expect(request.mock.calls.at(-1)![0]).toEqual({
    path: '/quality.exception/view/parent%2Fone/relations/lines/child%2Fone/content/description/translations/de/generation',
    method: 'POST',
    query: { retry: true },
  });
  expect(snapshot.version).toBe(4);
});

it('isolates in-flight source and reading requests between logical page sessions', async () => {
  const { loadBusinessContentReading } = await import('@/web-core/businessContent');
  const callbacks: ((value: unknown) => void)[] = [];
  const request = vi.fn(() => new Promise((resolve) => callbacks.push(resolve)));
  const http = { request } as unknown as import('@/web-core/http').HttpClient;
  const snapshot = { version: 4 };
  const oldSource = loadBusinessContentSources(http, 'demo.issue', 'one', undefined, snapshot, 1);
  const newSource = loadBusinessContentSources(http, 'demo.issue', 'one', undefined, snapshot, 2);
  const oldRead = loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot, 1);
  const newRead = loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot, 2);
  expect(newSource).not.toBe(oldSource);
  expect(newRead).not.toBe(oldRead);
  callbacks[0]!({});
  callbacks[2]!({});
  await Promise.all([oldSource, oldRead]);
  expect(loadBusinessContentSources(http, 'demo.issue', 'one', undefined, snapshot, 2)).toBe(newSource);
  expect(loadBusinessContentReading(http, 'demo.issue', 'one', 'en', undefined, snapshot, 2)).toBe(newRead);
  callbacks[1]!({});
  callbacks[3]!({});
  await Promise.all([newSource, newRead]);
  expect(request).toHaveBeenCalledTimes(4);
});

it('routes current and archived workflow opinions through their owning event while retaining the shared transport', async () => {
  const { loadBusinessContentReading, prepareBusinessContentTranslation, requestBusinessContentGeneration } =
    await import('@/web-core/businessContent');
  const request = vi.fn().mockResolvedValue({ state: 'UNAVAILABLE' });
  const http = { request };
  const current = { kind: 'workflowEvent' as const, workflowInstanceId: 'instance/one' };
  const archived = { ...current, history: true };
  expect(businessContentPath('quality.exception', 'event/one', current)).toBe(
    '/workflow/runtime/instance/instance%2Fone/events/event%2Fone/content',
  );
  await loadBusinessContentReading(http, 'quality.exception', 'event/one', 'de', archived);
  await prepareBusinessContentTranslation(
    http,
    'quality.exception',
    'event/one',
    'reason',
    'en',
    'edit',
    archived,
  );
  await requestBusinessContentGeneration(http, 'quality.exception', 'event/one', 'reason', 'de', false, {
    relation: current,
  });
  expect(request.mock.calls.map(([entry]) => entry.path)).toEqual([
    '/workflow/history/instance%2Fone/events/event%2Fone/content/reading',
    '/workflow/history/instance%2Fone/events/event%2Fone/content/reason/translations/en/prepare',
    '/workflow/runtime/instance/instance%2Fone/events/event%2Fone/content/reason/translations/de/generation',
  ]);
  expect(request.mock.calls[0][0].query).toEqual({ language: 'de' });
  expect(request.mock.calls[2][0]).toMatchObject({ method: 'POST', query: { retry: false } });
});
