import { defineComponent, h, nextTick } from 'vue';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import { flushPromises, shallowMount, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { configureModuleContext, type HttpClient } from '@/web-core';
import CurrentUserProfileDialog from '@/app/CurrentUserProfileDialog.vue';

it('loads employee capabilities only when an employee avatar is rendered', async () => {
  const request = vi.fn(async () => ({ moduleAlias: 'iam.employee', capabilities: [], actions: [] }));
  configureModuleContext({ http: { request } as HttpClient });
  const wrapper = shallowMount(CurrentUserProfileDialog, {
    global: { stubs: { UiModal: { template: '<div><slot /></div>' } } },
  });
  try {
    await flushPromises();
    expect(request).not.toHaveBeenCalled();
    await wrapper.setProps({ open: true, profile: { username: 'buyer' } });
    expect(request).not.toHaveBeenCalled();
    await wrapper.setProps({
      profile: { username: 'buyer', employee: { id: 'employee-1', title: '采购员' } },
    });
    await flushPromises();
    expect(request).toHaveBeenCalledExactlyOnceWith({ path: '/platform.module/iam.employee/context' });
    expect(wrapper.findComponent({ name: 'SingleImageFileReferenceField' }).exists()).toBe(true);
  } finally {
    wrapper.unmount();
  }
});

it('translates profile labels through the consumer scope while retaining employee and contact values', async () => {
  configureModuleContext({
    http: {
      request: async () => ({ moduleAlias: 'iam.employee', capabilities: [], actions: [] }),
    } as HttpClient,
  });
  const language = createLanguageContext({ languageTag: 'en' });
  const profile = {
    username: '原始账户',
    employee: {
      id: 'employee-original',
      title: '原始职员',
      enabled: true,
      mobile: '13800000000',
      email: 'original@example.test',
    },
  };
  const Host = defineComponent({
    setup() {
      provideLanguageContext(language);
      return () => h(CurrentUserProfileDialog, { open: true, profile });
    },
  });
  const wrapper = mount(Host, {
    global: { stubs: { UiModal: { template: '<div><slot /></div>' }, SingleImageFileReferenceField: true } },
  });
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('Contact details');
    expect(wrapper.text()).toContain('Employee profile');
    expect(wrapper.text()).toContain('原始账户');
    expect(wrapper.text()).toContain('原始职员');
    const inputs = wrapper.findAll('input');
    const original = inputs.map((input) => (input.element as HTMLInputElement).value);
    language.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.text()).toContain('联系方式');
    expect(inputs.map((input) => (input.element as HTMLInputElement).value)).toEqual(original);
    expect(profile.employee.mobile).toBe('13800000000');
    expect(wrapper.findComponent(CurrentUserProfileDialog).emitted('submit')).toBeUndefined();
  } finally {
    wrapper.unmount();
  }
});
