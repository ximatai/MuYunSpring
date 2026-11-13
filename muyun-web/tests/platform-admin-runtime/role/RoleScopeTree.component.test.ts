import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import {
  codeOperationErrorMessage,
  createLanguageContext,
  provideLanguageContext,
  provideModuleContextConfig,
  type LanguageContext,
} from '@muyun/web-core';
import RoleScopeTree from '@/platform-admin-runtime/role/RoleScopeTree.vue';
import { UiTree } from '@muyun/vue-ui-antdv';
import type { ModulePageNavigatorExtensionContext } from '@muyun/dynamic-page-runtime';

const backend = vi.hoisted(() => ({ request: vi.fn() }));

function createHost(context: ModulePageNavigatorExtensionContext, language: LanguageContext) {
  return defineComponent({
    setup() {
      provideLanguageContext(language);
      provideModuleContextConfig({ httpFactory: () => backend });
      return () => h(RoleScopeTree, { context });
    },
  });
}

it('refreshes cached scope labels without selecting again or fetching directory facts', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const source = codeOperationErrorMessage(language, 'iam.role.scope.platform', '平台角色').source;
  backend.request.mockResolvedValue({
    directSelections: [
      {
        selectionKey: 'platform',
        title: '平台角色',
        titleText: {
          source,
          languages: {
            en: {
              text: 'Platform roles',
              requestedTag: 'en',
              resourceTag: 'en',
              missing: false,
              fallback: false,
            },
          },
        },
      },
    ],
    navigations: [],
  });
  const context = {
    moduleAlias: 'iam.role',
    selectionKey: 'platform',
    selectSelectionKey: vi.fn(),
    refreshList: vi.fn(),
    reload: vi.fn(),
  };
  const Host = createHost(context, language);
  const wrapper = mount(Host, {
    global: {
      stubs: {
        RecordExplorerPanel: { template: '<section><slot /></section>' },
        UiSpin: { template: '<section><slot /></section>' },
        UiTree: {
          props: ['nodes'],
          template: '<ul><li v-for="node in nodes" :key="node.key">{{ node.title }}</li></ul>',
        },
      },
    },
  });
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('Platform roles');
    expect(context.selectSelectionKey).toHaveBeenCalledOnce();
    expect(context.selectSelectionKey.mock.calls[0]?.[0]).toBe('platform');
    expect(context.selectSelectionKey.mock.calls[0]?.[1]).toMatchObject({
      label: '平台角色',
      labelText: { source },
    });
    const calls = backend.request.mock.calls.length;
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.text()).toContain('平台角色');
    expect(wrapper.text()).not.toContain('Platform roles');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.text()).toContain('Platform roles');
    expect(backend.request.mock.calls.length).toBe(calls);
    expect(context.selectSelectionKey).toHaveBeenCalledOnce();
    expect(context.refreshList).not.toHaveBeenCalled();
    expect(context.reload).not.toHaveBeenCalled();
  } finally {
    wrapper.unmount();
  }
});

it.each([
  { key: 'platform', label: '平台角色', level: 'TENANT', navigationKey: 'role-scope-tenants' },
  { key: 'tenant:tenant-a', label: '当前租户', level: 'ORGANIZATION', navigationKey: 'tenant:tenant-a' },
])('retains the selected organization until explicitly switching to $key', async (scope) => {
  backend.request.mockReset();
  backend.request.mockResolvedValue({
    directSelections: [{ selectionKey: scope.key, title: scope.label }],
    navigations: [{ navigationKey: scope.navigationKey, level: scope.level, title: scope.level }],
  });
  const context = {
    moduleAlias: 'iam.role',
    selectionKey: 'organization:org-a',
    selectSelectionKey: vi.fn(),
    refreshList: vi.fn(),
    reload: vi.fn(),
  };
  const Host = createHost(context, createLanguageContext({ languageTag: 'zh-CN' }));
  const wrapper = mount(Host, {
    global: { stubs: { UiTree: true } },
  });
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('管理范围');
    const tree = wrapper.getComponent(UiTree);
    expect(tree.props('selectedKey')).toBe('organization:org-a');
    // UiTree is controlled: deselection must not fall back to a different business scope.
    tree.vm.$emit('deselect');
    await flushPromises();
    expect(context.selectSelectionKey).not.toHaveBeenCalled();
    expect(tree.props('selectedKey')).toBe('organization:org-a');
    expect(tree.props('nodes').map((node) => node.key)).toEqual([
      scope.key,
      `navigation:${scope.navigationKey}`,
    ]);
    tree.vm.$emit('select', { key: scope.key });
    expect(context.selectSelectionKey).toHaveBeenCalledExactlyOnceWith(scope.key, {
      label: scope.label,
      secondaryLabel: undefined,
    });
    expect(context.refreshList).not.toHaveBeenCalled();
    expect(context.reload).not.toHaveBeenCalled();
    expect(backend.request).toHaveBeenCalledOnce();
  } finally {
    wrapper.unmount();
  }
});
