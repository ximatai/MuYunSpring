import { describe, expect, it, vi } from 'vitest';
import { loadExplorerRecords } from '@/platform-components/loadExplorerRecords';
import type { WebPageResponse, WebQueryRequest } from '@muyun/web-contracts';

const batch = (pageNum: number, records: string[], totalKnown = true): WebPageResponse<string> => ({
  records,
  total: 3,
  pageNum,
  pageSize: 2,
  pages: 2,
  totalKnown,
});

describe('complete explorer query', () => {
  it('collects unknown totals using the server batch size and merges separate reading metadata', async () => {
    const query = vi.fn(
      async (request: WebQueryRequest): Promise<WebPageResponse<string>> => ({
        ...batch(request.page!.pageNum, request.page!.pageNum === 1 ? ['a', 'b'] : ['c'], false),
        businessContent: {
          language: 'en',
          missingRecords: 1,
          readings: request.page!.pageNum === 1 ? { a: { title: 'A' } } : { c: { title: 'C' } },
        },
      }),
    );
    const response = await loadExplorerRecords(
      query,
      { page: { pageNum: 1, pageSize: 200 }, externalQueryValues: { tenant: 't' } },
      () => true,
    );
    expect(query.mock.calls.map(([request]) => request.page)).toEqual([
      { pageNum: 1, pageSize: 200 },
      { pageNum: 2, pageSize: 2 },
    ]);
    expect(query.mock.calls[1]![0].externalQueryValues).toEqual({ tenant: 't' });
    expect(response).toMatchObject({
      records: ['a', 'b', 'c'],
      total: 3,
      totalKnown: true,
      businessContent: { missingRecords: 1, readings: { a: { title: 'A' }, c: { title: 'C' } } },
    });
  });

  it('stops stale work after an outstanding batch and never returns a partial navigation', async () => {
    let current = true;
    let finish!: (response: WebPageResponse<string>) => void;
    const query = vi.fn(
      () =>
        new Promise<WebPageResponse<string>>((resolve) => {
          finish = resolve;
        }),
    );
    const result = loadExplorerRecords(query, {}, () => current);
    current = false;
    finish(batch(1, ['old']));
    expect(await result).toBeUndefined();
    expect(query).toHaveBeenCalledTimes(1);
  });

  it('rejects a later batch failure rather than exposing a truncated tree', async () => {
    const query = vi
      .fn()
      .mockResolvedValueOnce(batch(1, ['a', 'b']))
      .mockRejectedValueOnce(new Error('offline'));
    await expect(loadExplorerRecords(query, {}, () => true)).rejects.toThrow('offline');
  });

  it('rejects a non-advancing server page instead of looping or duplicating records', async () => {
    await expect(
      loadExplorerRecords(
        async () => batch(1, ['a', 'b']),
        {},
        () => true,
      ),
    ).rejects.toThrow('Invalid navigation query page');
  });
});
