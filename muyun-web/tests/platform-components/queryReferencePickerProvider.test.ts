import { describe, expect, it, vi } from 'vitest';
import type { HttpClient } from '@/web-core';
import { createQueryReferencePickerProvider } from '@/platform-components/queryReferencePickerProvider';

describe('query reference picker provider', () => {
  it('uses only target REFERENCE transports for pages and bounded ID translation', async () => {
    const request = vi
      .fn()
      .mockResolvedValueOnce({
        records: [
          { id: 'customer-2', name: '杭州客户', enabled: true },
          { id: 'disabled', name: '停用客户', enabled: false },
        ],
        total: 21,
        pageNum: 2,
        pageSize: 20,
      })
      .mockResolvedValueOnce({
        records: [
          { id: 'customer-3', name: '宁波客户' },
          { id: 'customer-1', name: '上海客户' },
        ],
        total: 2,
        pageNum: 1,
        pageSize: 2,
      });
    const provider = createQueryReferencePickerProvider({
      http: { request } as unknown as HttpClient,
      reference: { targetModuleAlias: 'sales.customer', cardinality: 'ONE', labelField: 'name' },
    });

    await expect(
      provider.searchPage({ keyword: '杭州', pageNum: 2, pageSize: 20, scope: { selections: [] } }),
    ).resolves.toEqual({
      records: [
        { id: 'customer-2', title: '杭州客户', projections: { name: '杭州客户' }, disabled: false },
        { id: 'disabled', title: '停用客户', projections: { name: '停用客户' }, disabled: true },
      ],
      total: 21,
    });
    await expect(provider.resolve(['customer-1', 'customer-3'])).resolves.toEqual([
      { id: 'customer-1', title: '上海客户', projections: { name: '上海客户' }, disabled: false },
      { id: 'customer-3', title: '宁波客户', projections: { name: '宁波客户' }, disabled: false },
    ]);

    expect(request).toHaveBeenNthCalledWith(1, {
      method: 'POST',
      path: '/sales.customer/navigator/reference/query',
      body: { page: { pageNum: 2, pageSize: 20 }, quickSearch: '杭州' },
    });
    expect(request).toHaveBeenNthCalledWith(2, {
      method: 'POST',
      path: '/sales.customer/navigator/reference/translate',
      body: { ids: ['customer-1', 'customer-3'] },
    });
    expect(request.mock.calls.flat().map((call) => call.path)).not.toContain('/sales.customer/query');
    expect(request.mock.calls.flat().map((call) => call.path)).not.toContain(
      '/sales.customer/view/customer-1',
    );
  });

  it('normalizes dynamic reference records before recovering labels and enabled state', async () => {
    const request = vi.fn().mockResolvedValue({
      records: [{ id: 'dynamic-1', version: 1, values: { name: '动态客户', enabled: false }, children: {} }],
      total: 1,
      pageNum: 1,
      pageSize: 1,
    });
    const provider = createQueryReferencePickerProvider({
      http: { request } as unknown as HttpClient,
      reference: { targetModuleAlias: 'sales.customer', cardinality: 'ONE', labelField: 'name' },
    });

    await expect(provider.resolve(['dynamic-1'])).resolves.toEqual([
      { id: 'dynamic-1', title: '动态客户', projections: { name: '动态客户' }, disabled: true },
    ]);
  });

  it('marks an identifier used as the display title as a fallback', async () => {
    const request = vi.fn().mockResolvedValue({
      records: [{ id: 'internal-id' }],
      total: 1,
      pageNum: 1,
      pageSize: 20,
    });
    const provider = createQueryReferencePickerProvider({
      http: { request } as unknown as HttpClient,
      reference: { targetModuleAlias: 'sales.customer', cardinality: 'ONE' },
    });

    await expect(
      provider.searchPage({ keyword: '', pageNum: 1, pageSize: 20, scope: { selections: [] } }),
    ).resolves.toEqual({
      records: [{ id: 'internal-id', title: 'internal-id', identifierFallback: true, disabled: false }],
      total: 1,
    });
  });

  it('rejects unsupported browse axes rather than widening the target query', async () => {
    const provider = createQueryReferencePickerProvider({
      http: { request: vi.fn() } as unknown as HttpClient,
      reference: { targetModuleAlias: 'sales.customer', cardinality: 'ONE' },
    });

    await expect(
      provider.searchPage({
        keyword: '',
        pageNum: 1,
        pageSize: 20,
        scope: { selections: [{ axisId: 'region', itemId: 'east' }] },
      }),
    ).rejects.toMatchObject({
      kind: 'unsupportedConfiguration',
      retryable: false,
      message: '当前引用目标不支持范围导航',
    });
  });

  it('resolves every selected ID in bounded batches while preserving deduplicated input order', async () => {
    const request = vi.fn(async (options: { body: { ids: string[] } }) => {
      const ids = options.body.ids;
      return {
        records: [...ids].reverse().map((id) => ({ id, title: `客户 ${id}` })),
        total: ids.length,
        pageNum: 1,
        pageSize: ids.length,
      };
    });
    const provider = createQueryReferencePickerProvider({
      http: { request } as unknown as HttpClient,
      reference: { targetModuleAlias: 'sales.customer', cardinality: 'ONE' },
    });
    const ids = Array.from({ length: 201 }, (_, index) => `customer-${index + 1}`);

    await expect(provider.resolve([...ids, ids[0]!])).resolves.toEqual(
      ids.map((id) => ({ id, title: `客户 ${id}`, disabled: false })),
    );
    expect(request).toHaveBeenCalledTimes(3);
    expect(request.mock.calls.map(([call]) => call.body.ids)).toEqual([
      ids.slice(0, 100),
      ids.slice(100, 200),
      ids.slice(200),
    ]);
  });
});
