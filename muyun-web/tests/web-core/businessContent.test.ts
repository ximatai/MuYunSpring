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
