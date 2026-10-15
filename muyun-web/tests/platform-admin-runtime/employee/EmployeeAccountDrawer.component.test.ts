import { shallowMount, flushPromises } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import EmployeeAccountDrawer from '@/platform-admin-runtime/employee/EmployeeAccountDrawer.vue';

it('translates employee account controls without changing credentials or reloading bindings', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const request = vi
    .fn()
    .mockResolvedValueOnce(undefined)
    .mockResolvedValueOnce({
      data: { user: { id: 'bound-user' }, binding: { userId: 'bound-user' } },
    });
  const context = {
    module: { http: { request } },
    record: { id: 'employee-raw' },
    refreshList: vi.fn(),
  };
  const wrapper = shallowMount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () => h(EmployeeAccountDrawer, { context: context as never });
      },
    }),
    { global: { renderStubDefaultSlot: true, stubs: { EmployeeAccountDrawer: false } } },
  );
  await flushPromises();
  const drawer = wrapper.findComponent(EmployeeAccountDrawer);
  const inputs = drawer.findAllComponents({ name: 'UiInput' });
  await inputs[0]!.vm.$emit('update:value', ' account-original ');
  await inputs[1]!.vm.$emit('update:value', '原值-Secret!');
  expect(drawer.text()).toContain('Create and bind account');
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(drawer.text()).toContain('创建账号并绑定');
  language.setLanguage('en');
  await flushPromises();
  expect(inputs.map((input) => input.props('value'))).toEqual([' account-original ', '原值-Secret!']);
  expect(request).toHaveBeenCalledTimes(1);
  await drawer.get('form').trigger('submit');
  await flushPromises();
  expect(request).toHaveBeenLastCalledWith({
    method: 'POST',
    path: '/iam.employee/employee-raw/account/provision',
    body: { username: 'account-original', password: '原值-Secret!' },
  });
  expect(request).toHaveBeenCalledTimes(2);
  expect(context.refreshList).toHaveBeenCalledOnce();
  wrapper.unmount();
});
