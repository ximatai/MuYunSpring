import { flushPromises, shallowMount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import { describe, expect, it, vi } from 'vitest';
import TenantApplicationsDetailSection from '@/platform-admin-runtime/tenant/TenantApplicationsDetailSection.vue';
import TenantApplicationConfigurationDrawer from '@/platform-admin-runtime/tenant/TenantApplicationConfigurationDrawer.vue';

describe('tenant detail extensions', () => {
  it('reloads the provisioned-application section when the standard detail extension refreshes', async () => {
    const request = vi
      .fn()
      .mockImplementation((request) =>
        request.path === '/platform.application/query'
          ? Promise.resolve({ records: [{ alias: 'iam', title: '身份权限' }] })
          : Promise.resolve({ records: [{ applicationAlias: 'iam' }] }),
      );
    const context = {
      module: { http: { request } },
      record: { id: 'tenant-a' },
      refreshList: vi.fn(),
      refreshKey: 0,
      reload: vi.fn(),
    };
    const wrapper = shallowMount(TenantApplicationsDetailSection, { props: { context: context as never } });

    await flushPromises();
    expect(request).toHaveBeenCalledTimes(2);
    expect(wrapper.findComponent({ name: 'UiTagList' }).props('items')).toEqual([
      { key: 'iam', label: '身份权限 · iam' },
    ]);
    await wrapper.setProps({ context: { ...context, refreshKey: 1 } as never });
    await flushPromises();
    expect(request).toHaveBeenCalledTimes(4);
  });

  it('does not retain a previous tenant application summary when a later load fails', async () => {
    const request = vi
      .fn()
      .mockResolvedValueOnce({ records: [{ alias: 'iam', title: '身份权限' }] })
      .mockResolvedValueOnce({ records: [{ applicationAlias: 'iam' }] })
      .mockRejectedValueOnce(new Error('应用目录读取失败'))
      .mockRejectedValueOnce(new Error('租户应用读取失败'));
    const context = {
      module: { http: { request } },
      record: { id: 'tenant-a' },
      refreshList: vi.fn(),
      refreshKey: 0,
      reload: vi.fn(),
    };
    const wrapper = shallowMount(TenantApplicationsDetailSection, { props: { context: context as never } });

    await flushPromises();
    expect(wrapper.findComponent({ name: 'UiTagList' }).props('items')).toEqual([
      { key: 'iam', label: '身份权限 · iam' },
    ]);
    await wrapper.setProps({ context: { ...context, record: { id: 'tenant-b' } } as never });
    await flushPromises();

    expect(wrapper.findComponent({ name: 'UiTagList' }).props('items')).toEqual([]);
  });

  it('keeps drawer title actions synchronized while saving and refreshes detail extensions after success', async () => {
    let resolveConfigure: (() => void) | undefined;
    const request = vi
      .fn()
      .mockResolvedValueOnce({ records: [{ alias: 'iam', enabled: true }] })
      .mockResolvedValueOnce({ records: [{ applicationAlias: 'iam' }] })
      .mockImplementationOnce(
        () =>
          new Promise<void>((resolve) => {
            resolveConfigure = resolve;
          }),
      );
    const titleActions: Array<{ key: string; disabled?: boolean; loading?: boolean; run: () => unknown }> =
      [];
    const setCloseBlocked = vi.fn();
    const context = {
      module: { http: { request } },
      record: { id: 'tenant-a' },
      refreshList: vi.fn(),
      refreshDetailExtensions: vi.fn(),
      setCloseBlocked,
      close: vi.fn(),
      reload: vi.fn(),
      setTitleActions(actions: typeof titleActions) {
        titleActions.splice(0, titleActions.length, ...actions);
      },
    };
    shallowMount(TenantApplicationConfigurationDrawer, { props: { context: context as never } });
    await flushPromises();

    void titleActions.find((action) => action.key === 'confirm')!.run();
    await Promise.resolve();
    expect(titleActions.find((action) => action.key === 'confirm')).toMatchObject({
      disabled: true,
      loading: true,
    });
    expect(titleActions.find((action) => action.key === 'cancel')?.disabled).toBe(true);
    expect(setCloseBlocked).toHaveBeenLastCalledWith(true);

    resolveConfigure?.();
    await flushPromises();
    expect(context.refreshDetailExtensions).toHaveBeenCalledOnce();
    expect(context.refreshList).toHaveBeenCalledOnce();
    expect(context.close).toHaveBeenCalledOnce();
    expect(setCloseBlocked).toHaveBeenLastCalledWith(false);
  });
});

it('refreshes translated tenant application columns and title actions while preserving selection and writes', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const request = vi.fn().mockImplementation(({ path }) =>
    Promise.resolve(
      path === '/platform.application/query'
        ? {
            records: [
              { alias: 'iam', title: '身份权限', enabled: true },
              { alias: 'custom', title: '业务应用', enabled: true },
            ],
          }
        : { records: [] },
    ),
  );
  let actions: Array<{ key: string; label: string; run: () => unknown }> = [];
  const context = {
    module: { http: { request } },
    record: { id: 'tenant-raw' },
    refreshList: vi.fn(),
    refreshDetailExtensions: vi.fn(),
    close: vi.fn(),
    setCloseBlocked: vi.fn(),
    setTitleActions(next: typeof actions) {
      actions = next;
    },
  };
  const wrapper = shallowMount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(TenantApplicationConfigurationDrawer, { context: context as never });
      },
    }),
    { global: { stubs: { TenantApplicationConfigurationDrawer: false } } },
  );
  await flushPromises();
  const drawer = wrapper.findComponent(TenantApplicationConfigurationDrawer);
  const table = drawer.findComponent({ name: 'UiDataTable' });
  expect(table.props('columns').map((column: { title: string }) => column.title)).toEqual([
    'Application name',
    'Application alias',
  ]);
  table.props('selection').onChange(['iam', 'custom']);
  await flushPromises();
  const rows = table.props('rows');
  expect(actions.map((action) => action.label)).toEqual(['Cancel', 'Confirm']);
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(table.props('columns').map((column: { title: string }) => column.title)).toEqual([
    '应用名称',
    '应用 alias',
  ]);
  expect(actions.map((action) => action.label)).toEqual(['取消', '确认']);
  language.setLanguage('en');
  await flushPromises();
  expect(table.props('rows')).toBe(rows);
  expect(table.props('selection').selectedRowKeys).toEqual(['iam', 'custom']);
  expect(table.props('selection').disabledOf(rows[0])).toBe(true);
  expect(request).toHaveBeenCalledTimes(2);
  await actions.find((action) => action.key === 'confirm')!.run();
  expect(request).toHaveBeenLastCalledWith({
    method: 'POST',
    path: '/iam.tenant/tenant-raw/applications/configure',
    body: { applicationAliases: ['iam', 'custom'] },
  });
  expect(request).toHaveBeenCalledTimes(3);
  wrapper.unmount();
});
