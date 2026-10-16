import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import {
  codeOperationErrorMessage,
  createLanguageContext,
  provideLanguageContext,
  provideModuleContextConfig,
} from '@muyun/web-core';
import RoleScopeTree from '@/platform-admin-runtime/role/RoleScopeTree.vue';

const backend = vi.hoisted(() => ({ request: vi.fn() }));

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
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      provideModuleContextConfig({ httpFactory: () => backend });
      return () => h(RoleScopeTree, { context });
    },
  });
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
