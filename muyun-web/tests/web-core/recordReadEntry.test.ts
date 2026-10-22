import { afterEach, describe, expect, it, vi } from 'vitest';
import { computed, effectScope, nextTick, ref } from 'vue';
import { resolveRecordReadEntry } from '@/web-core/recordReadEntry';
import { useRecordReadSession } from '@/web-core/useRecordReadSession';
import { withHttpHeaders, type HttpClient } from '@/web-core/http';
import type { RecordReadQuery, RecordReadView } from '@/web-core/recordReadView';
import type { MenuRecord, PageDescriptor, ResolvedModuleUiDescriptor } from '@/web-contracts';
import {
  createMenuTab,
  getMenuNavigationTarget,
  pageDescriptorFromUrl,
  pageDescriptorToUrl,
} from '@/platform-workbench/menuNavigation';
import { queryDeviceUseFixture } from '../../examples/business-web/src/fixtures/readonlyDeviceUse';
import { restoreWorkbenchSessionTabs, saveWorkbenchSessionTabs } from '@/app/workbenchSessionTabs';
import { restoreSessionWorkbenchTabs } from '@/app/workbenchStartup';
import { routeUrlWithOpenOptions } from '@/platform-workbench/workbenchNavigation';

const moduleAlias = 'example.device_use';
const viewCodes = ['current', 'history'];
function tab(view: string) {
  const menu = {
    id: `device.${view}`,
    title: view,
    moduleAlias,
    entryType: 'module',
    openMode: 'tab',
    entryParamsJson: JSON.stringify({ view, label: `${view} view` }),
    defaultUiConfigId: `${view}-ui`,
    defaultQueryTemplateId: `${view}-query`,
  } as MenuRecord;
  return createMenuTab(menu, getMenuNavigationTarget(menu)!);
}
function resolve(page: PageDescriptor) {
  return resolveRecordReadEntry({ moduleAlias, viewCodes, page });
}
const scopes: ReturnType<typeof effectScope>[] = [];
afterEach(() => scopes.splice(0).forEach((scope) => scope.stop()));
const settle = async () => {
  await nextTick();
  await Promise.resolve();
};

describe('named read entry through existing workbench navigation', () => {
  it('keeps same-module menus, parameters and presentation defaults distinct across refresh', () => {
    const current = tab('current');
    const history = tab('history');
    expect(current.key).not.toBe(history.key);
    expect(current.instanceKey).not.toBe(history.instanceKey);
    // Refresh uses the existing descriptor snapshot; private entry data stays out of URL.
    const restored = JSON.parse(JSON.stringify(history.pageDescriptor)) as PageDescriptor;
    expect(resolve(restored)).toMatchObject({
      viewCode: 'history',
      menuId: 'device.history',
      params: { view: 'history', label: 'history view' },
      defaultUiConfigId: 'history-ui',
      defaultQueryTemplateId: 'history-query',
    });
    expect(pageDescriptorToUrl(restored)).not.toMatch(/ui|query|EntryParams|InstanceKey/);
    expect(resolve(current.pageDescriptor!)).toMatchObject({ viewCode: 'current' });
  });
  it('preserves module-menu defaults when a consumer maps the module to a read workspace route', () => {
    const original = tab('history');
    const menu = {
      ...original.target,
      id: original.target!.menuId,
      title: original.title,
      entryType: 'module',
    } as MenuRecord;
    const mapped = createMenuTab(menu, original.target!, {
      businessModuleRoutes: { [moduleAlias]: '/business/device-use' },
    });
    expect(mapped.pageDescriptor?.pageType).toBe('business-route');
    expect(resolve(mapped.pageDescriptor!)).toMatchObject({
      viewCode: 'history',
      defaultUiConfigId: 'history-ui',
      defaultQueryTemplateId: 'history-query',
    });
    expect(pageDescriptorToUrl(mapped.pageDescriptor!)).toBe('/business/device-use');
    let saved: string | null = null;
    const storage = {
      getItem: () => saved,
      setItem: (_key: string, value: string) => {
        saved = value;
      },
      removeItem: () => {
        saved = null;
      },
    };
    saveWorkbenchSessionTabs({ userId: 'user-a', tabs: [mapped] }, storage);
    const snapshot = restoreWorkbenchSessionTabs('user-a', storage)!;
    const rebuilt = restoreSessionWorkbenchTabs(snapshot.tabs, [{ record: menu, children: [] }], {
      businessModuleRoutes: { [moduleAlias]: '/business/device-use' },
    });
    expect(resolve(rebuilt[0]!.pageDescriptor!)).toMatchObject({
      viewCode: 'history',
      defaultUiConfigId: 'history-ui',
      defaultQueryTemplateId: 'history-query',
    });
    expect(restoreWorkbenchSessionTabs('user-b', storage)).toBeUndefined();
  });

  it('rebuilds refreshed menu tabs from current visible menus and drops revoked entries', () => {
    const current = tab('current');
    const history = tab('history');
    const stale = {
      ...history,
      pageDescriptor: { ...history.pageDescriptor!, entryParamsJson: '{"view":"forged"}' },
    };
    const visible = [current, history].map((item) => ({
      record: {
        ...item.target,
        id: item.target!.menuId,
        title: item.title,
        entryType: 'module',
      } as MenuRecord,
      children: [],
    }));
    const restored = restoreSessionWorkbenchTabs([current, stale], visible);
    expect(restored).toHaveLength(2);
    expect(resolve(restored[1]!.pageDescriptor!).viewCode).toBe('history');
    expect(restoreSessionWorkbenchTabs([current, stale], [visible[0]!])).toHaveLength(1);
  });

  it('makes direct access explicit and rejects unknown, malformed or cross-module entries', () => {
    const url = routeUrlWithOpenOptions('/example/device-use', { query: { view: 'history' } });
    const direct = pageDescriptorFromUrl(url);
    expect(
      resolveRecordReadEntry({ moduleAlias, viewCodes, page: direct, directViewCode: 'history' }),
    ).toMatchObject({ viewCode: 'history', menuId: undefined });
    expect(resolveRecordReadEntry({ moduleAlias, viewCodes, defaultViewCode: 'current' }).viewCode).toBe(
      'current',
    );
    const businessDirect = pageDescriptorFromUrl('/business/device-use?view=history', {
      businessRoutePrefixes: ['/business'],
    });
    expect(
      resolveRecordReadEntry({ moduleAlias, viewCodes, page: businessDirect, directViewCode: 'history' })
        .viewCode,
    ).toBe('history');
    expect(() => resolveRecordReadEntry({ moduleAlias, viewCodes })).toThrow('Unknown read view');
    expect(() => resolveRecordReadEntry({ moduleAlias, viewCodes, directViewCode: 'admin' })).toThrow(
      'Unknown read view',
    );
    expect(() => resolve({ ...tab('history').pageDescriptor!, entryParamsJson: '{}' })).toThrow(
      'Unknown read view',
    );
    expect(() => resolve({ ...tab('history').pageDescriptor!, entryParamsJson: '[]' })).toThrow(
      'JSON object',
    );
    expect(() => resolve({ ...tab('history').pageDescriptor!, entryParamsJson: '{' })).toThrow();
    expect(() => resolveRecordReadEntry({ moduleAlias: 'other.module', viewCodes, page: direct })).toThrow(
      'module mismatch',
    );
    expect(() =>
      resolveRecordReadEntry({
        moduleAlias,
        viewCodes,
        page: tab('history').pageDescriptor!,
        directViewCode: 'current',
      }),
    ).toThrow('conflicts with menu');
  });
  it('keeps default filters caller-adjustable instead of treating them as mandatory scope', async () => {
    const entry = resolve(tab('current').pageDescriptor!);
    const query = ref<RecordReadQuery>({ conditions: [{ fieldName: 'status', values: ['CURRENT'] }] });
    const read = vi.fn<RecordReadView['query']>(async () => ({
      records: [],
      pageNum: 1,
      pageSize: 20,
      total: 0,
      pages: 0,
      totalKnown: true,
    }));
    const scope = effectScope();
    scopes.push(scope);
    scope.run(() =>
      useRecordReadSession({
        query,
        view: {
          sourceId: moduleAlias,
          viewCode: entry.viewCode,
          scopeKey: 'user:tenant',
          descriptor: { page: { list: { fields: { fields: [] } } } } as unknown as ResolvedModuleUiDescriptor,
          recordKey: (row) => String(row.id),
          query: read,
        },
      }),
    );
    await settle();
    expect(read).toHaveBeenLastCalledWith(
      expect.objectContaining({ conditions: [{ fieldName: 'status', values: ['CURRENT'] }] }),
      expect.anything(),
    );
    query.value = {};
    await settle();
    expect(read.mock.calls.at(-1)?.[0]).not.toHaveProperty('conditions');
    // An endpoint needing CURRENT as mandatory domain scope must enforce it server-side even now.
  });

  it('ANDs default filters with authored OR criteria, then sorts before paging in the local sample', () => {
    const request = {
      conditions: [{ fieldName: 'status', operator: 'EQ', values: ['CURRENT'] }],
      criteria: {
        kind: 'GROUP' as const,
        operator: 'OR' as const,
        children: [
          { kind: 'CONDITION' as const, fieldName: 'title', operator: 'LIKE' as const, values: ['02'] },
          {
            kind: 'CONDITION' as const,
            fieldName: 'title',
            operator: 'EQ' as const,
            values: ['设备事项 04'],
          },
          { kind: 'CONDITION' as const, fieldName: 'status', operator: 'EQ' as const, values: ['RETURNED'] },
        ],
      },
      sorts: [{ field: 'title', desc: true }],
      page: { pageNum: 1, pageSize: 1 },
    };
    const first = queryDeviceUseFixture(request);
    expect(first.total).toBe(2);
    expect(first.records.map((row) => row.title)).toEqual(['设备事项 04']);
    expect(
      queryDeviceUseFixture({ ...request, page: { pageNum: 2, pageSize: 1 } }).records.map(
        (row) => row.title,
      ),
    ).toEqual(['设备事项 02']);
    expect(queryDeviceUseFixture({}).total).toBe(25);
    expect(() => queryDeviceUseFixture({ conditions: [{ fieldName: 'secret', values: ['x'] }] })).toThrow(
      'Unsupported fixture field',
    );
  });

  it('passes only menu identity to transport for server revalidation', async () => {
    const request = vi.fn(async () => undefined);
    const http = withHttpHeaders({ request } as HttpClient, {
      'X-MuYun-Menu-Id': resolve(tab('history').pageDescriptor!).menuId,
    });
    await http.request({ path: '/example.device_use/query', headers: { 'x-muyun-menu-id': 'forged' } });
    expect(request).toHaveBeenCalledWith({
      path: '/example.device_use/query',
      headers: { 'X-MuYun-Menu-Id': 'device.history' },
    });
    // Transport contract only: authorization and fixed business scope are server duties.
  });
  it('isolates filters/detail per tab and invalidates both sessions on identity/tenant changes', async () => {
    const principal = ref('user-a');
    const tenant = ref('tenant-a');
    const calls = vi.fn();
    function session(page: PageDescriptor) {
      const entry = resolve(page);
      const scope = effectScope();
      scopes.push(scope);
      return scope.run(() => {
        const view = computed<RecordReadView>(() => ({
          sourceId: moduleAlias,
          viewCode: entry.viewCode,
          scopeKey: `${principal.value}:${tenant.value}`,
          descriptor: { page: { list: { fields: { fields: [] } } } } as unknown as ResolvedModuleUiDescriptor,
          recordKey: (row) => String(row.id),
          async query(request) {
            calls(entry.viewCode, request, `${principal.value}:${tenant.value}`);
            return {
              records: [{ id: entry.viewCode, title: `${principal.value}:${tenant.value}` }],
              pageNum: request.page!.pageNum!,
              pageSize: 20,
              total: 41,
              pages: 3,
              totalKnown: true,
            };
          },
          detail: { title: (row) => String(row.title), load: async (row) => row },
        }));
        return useRecordReadSession({ view });
      })!;
    }
    const current = session(tab('current').pageDescriptor!);
    const history = session(tab('history').pageDescriptor!);
    await settle();
    await current.search('Alice');
    await current.goPage(2);
    await current.select(current.result.value!.records[0]!);
    expect(history.quickSearch.value).toBe('');
    expect(history.pageNum.value).toBe(1);
    expect(history.selected.value).toBeUndefined();
    expect(current.detail.value).toBeDefined();
    principal.value = 'user-b';
    await settle();
    expect(current.pageNum.value).toBe(1);
    expect(current.quickSearch.value).toBe('');
    expect(current.detail.value).toBeUndefined();
    expect(history.result.value?.records[0]?.title).toBe('user-b:tenant-a');
    tenant.value = 'tenant-b';
    await settle();
    expect(current.result.value?.records[0]?.title).toBe('user-b:tenant-b');
    expect(history.result.value?.records[0]?.title).toBe('user-b:tenant-b');
    expect(calls).toHaveBeenLastCalledWith('history', expect.anything(), 'user-b:tenant-b');
  });
});
