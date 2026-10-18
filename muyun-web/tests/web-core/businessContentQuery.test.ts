import { expect, it } from 'vitest';
import {
  createModuleCrudClient,
  createNavigatorReferenceCrudClient,
  createManagedDetailRelationClient,
  type HttpClient,
  type HttpRequestOptions,
} from '@muyun/web-core';

it('carries reading intent separately from unchanged business query controls on standard query transports', async () => {
  const requests: HttpRequestOptions[] = [];
  const response = {
    records: [{ id: 'record', title: '原文' }],
    total: 1,
    pageNum: 1,
    pageSize: 20,
    pages: 1,
    totalKnown: true,
    businessContent: { language: 'de', missingRecords: 0, readings: { record: { title: 'Gerät' } } },
  };
  const http: HttpClient = {
    request: async <T>(request: HttpRequestOptions) => {
      requests.push(request);
      return response as T;
    },
  };
  const clients = [
    createModuleCrudClient(http, { moduleAlias: 'test.equipment' }),
    createNavigatorReferenceCrudClient(http, { moduleAlias: 'test.equipment' }),
    createManagedDetailRelationClient(http, {
      parentModuleAlias: 'test.issue',
      parentId: 'parent',
      relationCode: 'notes',
    }),
  ];
  const controls = { quickSearch: 'Gerät', page: { pageNum: 2, pageSize: 20 } };
  for (const client of clients) {
    const result = await client.query(controls, { contentLanguage: 'de' });
    expect(requests.at(-1)?.headers).toEqual({
      'X-MuYun-Content-Language': 'de',
      'X-MuYun-Content-Languages': 'zh-CN,en,de',
    });
    expect(requests.at(-1)?.body).toMatchObject(controls);
    expect(result.records[0]).toEqual({ id: 'record', title: '原文' });
    expect(result.businessContent).toEqual(response.businessContent);
  }
  await clients[0]!.query(controls);
  expect(requests.at(-1)?.headers).toEqual({ 'X-MuYun-Content-Languages': 'zh-CN,en,de' });
});
