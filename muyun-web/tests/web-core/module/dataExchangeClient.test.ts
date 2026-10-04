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
