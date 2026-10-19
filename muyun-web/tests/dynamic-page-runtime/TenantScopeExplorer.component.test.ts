import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import TenantScopeExplorer from '@/dynamic-page-runtime/TenantScopeExplorer.vue';
import { createHttpClient, createModuleContext } from '@muyun/web-core';
import type { QueryListRecord } from '@muyun/platform-components';

describe('TenantScopeExplorer', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('loads all tenant candidates and keeps remote search distinct from the initial full result', async () => {
    const searches: string[] = [];
    const urls: string[] = [];
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = new Request(input, init);
      urls.push(request.url);
      if (request.url.endsWith('/platform.module/iam.tenant/reference-context'))
        return Response.json({
          moduleAlias: 'iam.tenant',
          capabilities: [],
          abilities: ['crud'],
          actions: [],
        });
      if (request.url.endsWith('/iam.tenant/navigator/reference/query')) {
        const body = JSON.parse(String(init?.body ?? '{}')) as {
          quickSearch?: string;
          page: { pageNum: number; pageSize: number };
        };
        searches.push(body.quickSearch ?? '');
        if (body.quickSearch === '第201')
          return Response.json({
            records: [{ id: 'tenant-201', title: '第201个租户' }],
            total: 1,
            pageNum: 1,
            pageSize: 200,
            pages: 1,
            totalKnown: true,
          });
        return Response.json({
          records:
            body.page.pageNum === 1
              ? Array.from({ length: 200 }, (_, index) => ({
                  id: `tenant-${index + 1}`,
                  title: `租户${index + 1}`,
                }))
              : [{ id: 'tenant-201', title: '第201个租户' }],
          total: 201,
          pageNum: body.page.pageNum,
          pageSize: 200,
          pages: 2,
          totalKnown: true,
        });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    });
    const context = createModuleContext<QueryListRecord>({
      moduleAlias: 'iam.tenant',
      http: createHttpClient({ baseUrl: 'http://api.local' }),
      runtimeAccess: 'REFERENCE',
    });
    const wrapper = mount(TenantScopeExplorer, { props: { context, reloadKey: 0 } });
    await flushPromises();
    expect(wrapper.findComponent({ name: 'CrudRecordListExplorer' }).exists()).toBe(true);

    await wrapper.find('[title="搜索租户"]').trigger('click');
    await wrapper.find('input').setValue('第201');
    await flushPromises();

    expect({ searches, urls }).toEqual({
      searches: ['', '', '第201'],
      urls: [
        'http://api.local/platform.module/iam.tenant/reference-context',
        'http://api.local/iam.tenant/navigator/reference/query',
        'http://api.local/iam.tenant/navigator/reference/query',
        'http://api.local/iam.tenant/navigator/reference/query',
      ],
    });
    expect(wrapper.text()).toContain('第201个租户');
    expect(wrapper.get('.management-panel-header-title-group').text()).toBe('租户');
    expect(wrapper.find('.crud-record-list-pagination').exists()).toBe(false);
    expect(wrapper.emitted('loaded')).toEqual([
      [expect.any(Array), true, 201],
      [[{ id: 'tenant-201', title: '第201个租户' }], false, 1],
    ]);
  });
});
