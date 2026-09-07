import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import ModuleMenuDrawer from '@/platform-admin-runtime/module-menu/ModuleMenuDrawer.vue';
import { provideWorkbenchNavigation } from '@/platform-workbench/workbenchNavigation';
import { provideModuleContextConfig } from '@/web-core';
import { UiInput } from '@/vue-ui-antdv';

function setup(
  kind: 'static' | 'dynamic',
  options: {
    failSave?: boolean;
    failRefresh?: boolean;
    hidden?: boolean;
    entry?: Record<string, unknown>;
  } = {},
) {
  const menu = {
    id: 'new',
    title: '客户',
    schemeId: 'scheme',
    moduleAlias: 'crm.customer',
    enabled: true,
    entryType: 'module',
    openMode: 'tab',
  };
  const mine = [{ record: menu, children: [] }];
  let rejectSave = options.failSave;
  const request = vi.fn(async ({ path }: { path: string }) => {
    if (path.endsWith('/context'))
      return {
        moduleAlias: 'crm.customer',
        capabilities: [],
        actions: [],
        uiDescriptor: { page: { template: 'management' } },
        ...options.entry,
      };
    if (path === '/platform.menu_scheme/query')
      return { records: [{ id: 'scheme', title: '业务菜单' }], total: 1 };
    if (path === '/platform.menu/mine') return { records: [] };
    if (path === '/platform.menu/tree/query') return { records: [] };
    if (path === '/platform.menu/insert') {
      if (rejectSave) {
        rejectSave = false;
        throw new Error('暂时无法保存');
      }
      return menu;
    }
    throw new Error(path);
  });
  const openMenu = vi.fn();
  const refreshMenus = vi.fn(async () => {
    if (options.failRefresh) throw new Error('offline');
    return options.hidden ? [] : mine;
  });
  const context = {
    record: { id: 'crm.customer', alias: 'crm.customer', title: '客户', moduleKind: kind },
    module: { http: { request } },
    close: vi.fn(),
    refreshDetailExtensions: vi.fn(),
    setCloseBlocked: vi.fn(),
    setTitleActions: vi.fn(),
  };
  const wrapper = mount(
    defineComponent({
      setup() {
        provideModuleContextConfig({ http: { request: request as never } });
        provideWorkbenchNavigation({ openMenu, refreshMenus } as never);
        return () => h(ModuleMenuDrawer, { context: context as never });
      },
    }),
  );
  const save = async () => {
    const actions = context.setTitleActions.mock.lastCall![0];
    expect(actions[0].disabled).toBe(false);
    await actions[0].run();
    await flushPromises();
  };
  return { wrapper, context, request, openMenu, refreshMenus, save };
}

describe('adding a module menu', () => {
  it.each(['static', 'dynamic'] as const)('uses the same lightweight flow for a %s module', async (kind) => {
    const test = setup(kind);
    await flushPromises();
    expect(test.wrapper.text()).not.toContain('菜单方案');
    expect(test.wrapper.find('details').attributes('open')).toBeUndefined();
    expect(test.wrapper.findComponent(UiInput).props('value')).toBe('客户');
    await test.save();
    expect(test.request).toHaveBeenCalledWith(
      expect.objectContaining({
        path: '/platform.menu/insert',
        body: expect.objectContaining({ moduleAlias: 'crm.customer', parentId: 'root', openMode: 'tab' }),
      }),
    );
    expect(test.wrapper.text()).toContain('业务菜单 / 客户');
    await test.wrapper
      .findAll('button')
      .find((button) => button.text().replace(/\s/g, '') === '打开')!
      .trigger('click');
    expect(test.openMenu).toHaveBeenCalledWith(expect.objectContaining({ id: 'new' }));
    expect(test.context.refreshDetailExtensions).toHaveBeenCalledOnce();
    test.wrapper.unmount();
  });

  it('blocks a module without a published or declared page and provides a return action', async () => {
    const test = setup('dynamic', { entry: { uiDescriptor: null } });
    await flushPromises();
    expect(test.context.setTitleActions.mock.lastCall![0][0].disabled).toBe(true);
    expect(test.wrapper.text()).toContain('尚无可用业务页面');
    expect(test.request.mock.calls.some(([call]) => call.path.endsWith('/insert'))).toBe(false);
    test.wrapper.unmount();
  });

  it.each([
    { entryType: 'route', entryRoute: '/business/customer', uiDescriptor: null },
    { entryType: 'link', entryExternalUrl: 'https://example.com', uiDescriptor: null },
  ])('accepts declared route/link entries without requiring a standard page', async (entry) => {
    const test = setup('static', { entry });
    await flushPromises();
    await test.save();
    expect(test.wrapper.text()).toContain('已添加到菜单');
    test.wrapper.unmount();
  });

  it('preserves entered values and allows retry after a failed save', async () => {
    const test = setup('dynamic', { failSave: true });
    await flushPromises();
    test.wrapper.findComponent(UiInput).vm.$emit('update:value', '我的客户');
    await flushPromises();
    await test.save();
    expect(test.wrapper.text()).toContain('暂时无法保存');
    expect(test.wrapper.findComponent(UiInput).props('value')).toBe('我的客户');
    await test.save();
    expect(test.wrapper.text()).toContain('已添加到菜单');
    test.wrapper.unmount();
  });

  it('does not offer another insert when creation succeeded but navigation refresh failed', async () => {
    const test = setup('static', { failRefresh: true });
    await flushPromises();
    await test.save();
    expect(test.wrapper.text()).toContain('无需重复添加');
    expect(test.context.setTitleActions.mock.lastCall![0]).toEqual([]);
    expect(test.request.mock.calls.filter(([call]) => call.path.endsWith('/insert'))).toHaveLength(1);
    test.wrapper.unmount();
  });

  it('explains a saved entry outside the current visible navigation without offering a bypass', async () => {
    const test = setup('static', { hidden: true });
    await flushPromises();
    await test.save();
    expect(test.wrapper.text()).toContain('暂不可见');
    expect(test.wrapper.findAll('button').some((button) => button.text().replace(/\s/g, '') === '打开')).toBe(
      false,
    );
    test.wrapper.unmount();
  });
});
