import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { createLanguageContext } from '@muyun/web-core';
import {
  createModuleMenuSession,
  type ModuleMenuClient,
} from '@/platform-admin-runtime/module-menu/moduleMenuSession';
import ModuleMenuEditor from '@/platform-admin-runtime/module-menu/ModuleMenuEditor.vue';

it('uses the session language while retaining manual input and selection identities', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const context = vi.fn(async () => ({
    title: '业务原名',
    actions: [{ actionCode: 'create', authorized: true }],
    uiDescriptor: { page: {} },
  }));
  const client = {
    context,
    schemes: vi.fn(async () => [
      { id: 'a', title: '业务方案原名', scopeType: 'tenant', tenantId: 'tenant-1' },
      { id: 'b', title: '其他方案原名' },
    ]),
    tree: vi.fn(async () => []),
    visible: vi.fn(async () => []),
  } as unknown as ModuleMenuClient;
  const session = createModuleMenuSession(
    client,
    'business.order',
    { valid: () => true, active: () => true },
    { restore: async () => undefined, save: async () => {}, clear: async () => {} },
    undefined,
    language,
  );
  await session.load();
  const wrapper = mount(ModuleMenuEditor, {
    props: { session, showSave: true },
    global: {
      stubs: {
        UiInput: {
          props: ['value'],
          emits: ['update:value'],
          template: '<input :value="value" @input="$emit(\'update:value\', $event.target.value)" />',
        },
        UiSelect: { name: 'UiSelect', props: ['options'], template: '<span>{{ options }}</span>' },
        UiTreeSelect: { template: '<span />' },
        UiButton: { template: '<button><slot /></button>' },
      },
    },
  });
  try {
    expect(wrapper.text()).toContain('Menu name');
    expect(wrapper.text()).toContain('Users in tenant tenant-1');
    await wrapper.find('input').setValue('手工菜单原名');
    await flushPromises();
    const revision = session.revision.value;
    const calls = context.mock.calls.length;
    language.setLanguage('zh-CN');
    await flushPromises();
    expect(wrapper.text()).toContain('菜单名称');
    expect(wrapper.text()).toContain('租户 tenant-1 的用户');
    expect(session.title.value).toBe('手工菜单原名');
    language.setLanguage('en');
    await flushPromises();
    expect(wrapper.text()).toContain('Menu name');
    expect(wrapper.text()).toContain('业务方案原名');
    expect(session.schemeId.value).toBe('a');
    expect(session.revision.value).toBe(revision);
    expect(context).toHaveBeenCalledTimes(calls);
  } finally {
    wrapper.unmount();
    session.dispose();
  }
});
