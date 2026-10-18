import { afterEach, expect, it, vi } from 'vitest';
import { createHttpClient, createDataExchangeClient } from '@muyun/web-core';

afterEach(() => vi.unstubAllGlobals());

it('downloads Excel bytes through the authenticated HTTP client and retains structured errors', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(new Uint8Array([0x50, 0x4b, 0, 255]), {
        headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' },
      }),
    )
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ code: 'ACCESS_DENIED', message: '没有导入权限' }), {
        status: 403,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  vi.stubGlobal('fetch', fetcher);
  const client = createDataExchangeClient(
    createHttpClient({
      baseUrl: 'http://localhost/api',
      token: 'current-user',
      headers: { 'X-MuYun-Tenant-Id': 'tenant-a' },
    }),
    'sales.order',
  );
  const blob = await client.template();
  expect([...new Uint8Array(await blob.arrayBuffer())]).toEqual([0x50, 0x4b, 0, 255]);
  expect(fetcher.mock.calls[0]?.[1].headers).toMatchObject({
    Authorization: 'Bearer current-user',
    'X-MuYun-Tenant-Id': 'tenant-a',
  });
  await expect(client.errorFile('token')).rejects.toMatchObject({
    code: 'ACCESS_DENIED',
    status: 403,
    message: '没有导入权限',
  });
});

it('sends typed multipart commands and exports the full successful query instead of its current page', async () => {
  const request = vi.fn().mockResolvedValue({});
  const client = createDataExchangeClient({ request }, 'sales.order');
  const file = new File(['excel'], 'orders.xlsx');
  const command = {
    mainSheet: { matchFieldName: 'orderNo', duplicateStrategy: 'SKIP' as const },
    childSheets: [{ entityAlias: 'line', matchFieldName: 'sku', duplicateStrategy: 'OVERWRITE' as const }],
  };
  await client.execute(file, command);
  const upload = request.mock.calls[0]?.[0];
  expect(upload.path).toBe('/sales.order/import/execute');
  expect(upload.body).toBeInstanceOf(FormData);
  expect(JSON.parse(await (upload.body.get('command') as Blob).text())).toEqual(command);
  expect(upload.body.get('file').name).toBe('orders.xlsx');
  const query = {
    page: { pageNum: 3, pageSize: 20 },
    quickSearch: '晴川',
    uiConfigId: 'published-page',
    queryTemplateId: 'current-template',
    externalQueryValues: { customerId: 'customer-1' },
    queryForm: { enabled: true },
    sorts: [{ field: 'orderNo', desc: true }],
  };
  await client.exportData(query);
  expect(request.mock.calls[1]?.[0]).toMatchObject({
    path: '/sales.order/export/data',
    responseType: 'blob',
    body: { ...query, page: undefined, unpaged: true },
  });
  expect(query.page.pageNum).toBe(3);
});

it('binds review and import to explicit source intent, stable identities and object confirmations', async () => {
  const receipt = { created: 1, updated: 0, skipped: 0, errorCount: 0, partialSuccess: false, rows: [] };
  const request = vi
    .fn()
    .mockResolvedValueOnce({ documentId: 'document' })
    .mockResolvedValueOnce({ data: receipt, changes: [] });
  const client = createDataExchangeClient({ request }, 'quality.equipment_category');
  const file = new File(['xlsx'], 'classification.xlsx');
  const command = {
    documentId: 'document',
    previewDigest: 'reviewed-file-and-intent',
    resolutionLanguage: 'de',
    sourceLanguage: 'en',
    sheets: [{ entityAlias: 'main', matchField: 'code', duplicateStrategy: 'OVERWRITE' as const }],
    confirmations: [
      { row: 'row-identity', field: 'categoryId', input: 'Maschinenname', candidateId: 'category-id' },
    ],
  };
  await client.preview(file, command);
  expect(request.mock.calls[0]?.[0].path).toBe('/quality.equipment_category/exchange/files/preview');
  const body = request.mock.calls[0]?.[0].body as FormData;
  expect(JSON.parse(await (body.get('command') as Blob).text())).toEqual(command);
  expect(body.get('file')).toBe(file);
  expect(await client.importFile(file, command)).toEqual(receipt);
  const imported = request.mock.calls[1]?.[0];
  expect(imported.path).toBe('/quality.equipment_category/exchange/files/import');
  expect(JSON.parse(await (imported.body.get('command') as Blob).text())).toEqual(command);
});

it.each(['DATA', 'TRANSLATIONS', 'READING'] as const)(
  'exports %s files under the same query and explicit file language',
  async (purpose) => {
    const request = vi.fn().mockResolvedValue(new Blob(['xlsx']));
    const client = createDataExchangeClient({ request }, 'quality.exception');
    const query = {
      quickSearch: 'seal',
      uiConfigId: 'published-list',
      sorts: [{ field: 'title', desc: false }],
    };
    await client.exportFile(query, { purpose, language: 'de', timeZone: 'Europe/Berlin' }, undefined, {
      contentLanguage: 'en',
    });
    expect(request.mock.calls[0]?.[0]).toEqual({
      method: 'POST',
      path: '/quality.exception/exchange/files/export',
      body: { query, options: { purpose, language: 'de', timeZone: 'Europe/Berlin' }, ids: undefined },
      headers: { 'X-MuYun-Content-Languages': 'zh-CN,en,de', 'X-MuYun-Content-Language': 'en' },
      responseType: 'blob',
    });
    expect(query.quickSearch).toBe('seal');
  },
);
