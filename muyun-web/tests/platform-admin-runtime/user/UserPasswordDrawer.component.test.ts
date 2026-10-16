import { shallowMount, flushPromises } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import { presentPlatformMessage } from '@muyun/platform-components';
vi.mock('@muyun/platform-components', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@muyun/platform-components')>()),
  presentPlatformMessage: vi.fn(),
  presentPlatformError: vi.fn(),
}));
import UserPasswordDrawer from '@/platform-admin-runtime/user/UserPasswordDrawer.vue';

it('translates password controls while preserving the raw password, permissions and single write', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const request = vi.fn().mockResolvedValue(undefined);
  const context = {
    module: { http: { request }, can: () => true },
    record: { id: 'user-password' },
    setCloseBlocked: vi.fn(),
    refreshList: vi.fn(),
    refreshDetailExtensions: vi.fn(),
  };
  const wrapper = shallowMount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(UserPasswordDrawer, { context: context as never });
      },
    }),
    { global: { renderStubDefaultSlot: true, stubs: { UserPasswordDrawer: false } } },
  );
  const drawer = wrapper.findComponent(UserPasswordDrawer);
  const input = drawer.findComponent({ name: 'UiInput' });
  await input.vm.$emit('update:value', '原值-Secret!');
  expect(drawer.text()).toContain('Generate temporary password');
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(drawer.text()).toContain('生成临时密码');
  expect(input.props('value')).toBe('原值-Secret!');
  language.setLanguage('en');
  await flushPromises();
  expect(request).not.toHaveBeenCalled();
  const change = drawer
    .findAllComponents({ name: 'UiButton' })
    .find((button) => button.text() === 'Change password')!;
  await change.vm.$emit('click');
  await flushPromises();
  expect(request).toHaveBeenCalledExactlyOnceWith({
    method: 'POST',
    path: '/iam.user/changePassword/user-password',
    body: { password: '原值-Secret!' },
  });
  expect(context.setCloseBlocked.mock.calls).toEqual([[true], [false]]);
  expect(input.props('value')).toBe('');
  expect(presentPlatformMessage).toHaveBeenCalledWith(
    'Password changed.',
    expect.objectContaining({ language }),
  );
  wrapper.unmount();
});
