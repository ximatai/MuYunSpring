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

it('uploads, replaces and clears the avatar before submitting the empty binding', async () => {
  let uploadCount = 0;
  const intents: string[] = [];
  const request = vi.fn(async ({ path, body }: { path: string; body?: unknown }) => {
    if (path.endsWith('/inline-upload')) {
      const upload = body as FormData;
      expect(upload.get('file')).toBeInstanceOf(File);
      const metadata = JSON.parse(String(upload.get('request')));
      expect(metadata.fieldName).toBe('avatarAssetId');
      intents.push(metadata.intent);
      return { items: [{ id: `avatar-${++uploadCount}` }] };
    }
    if (path.endsWith('/preview-ticket')) return { url: '/avatar.png' };
    return { moduleAlias: 'iam.employee', capabilities: [], actions: [] };
  });
  configureModuleContext({ http: { request } as HttpClient });
  const wrapper = mount(CurrentUserProfileDialog, {
    props: {
      open: true,
      profile: {
        username: 'buyer',
        employee: {
          id: 'employee-1',
          contactEditable: true,
          mobile: '13800000000',
          email: 'buyer@example.test',
        },
      },
    },
    global: {
      stubs: {
        UiModal: { template: `<div><slot /><button data-save @click="$emit('confirm')">保存</button></div>` },
      },
    },
  });
  async function upload(name: string) {
    const input = wrapper.get('input[type="file"]');
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: [new File(['image'], name, { type: 'image/png' })],
    });
    await input.trigger('change');
    await flushPromises();
  }
  try {
    expect(wrapper.find('.single-image-file-reference-field__label').exists()).toBe(false);
    await upload('first.png');
    const replace = wrapper.get('button[aria-label="替换图片"]');
    expect(replace.isVisible()).toBe(true);
    expect(replace.attributes('disabled')).toBeUndefined();
    const inputClick = vi.spyOn(wrapper.get('input[type="file"]').element as HTMLInputElement, 'click');
    await replace.trigger('click');
    expect(inputClick).toHaveBeenCalledOnce();
    await upload('replacement.png');
    await wrapper.get('[data-save]').trigger('click');
    expect(wrapper.emitted('submit')?.at(-1)).toEqual([
      { mobile: '13800000000', email: 'buyer@example.test', avatarAssetId: 'avatar-2' },
    ]);
    expect(intents).toEqual(['CREATE', 'REPLACE']);
    const clear = wrapper.get('button[aria-label="清除"]');
    expect(clear.isVisible()).toBe(true);
    await clear.trigger('click');
    expect(wrapper.find('button[aria-label="替换图片"]').exists()).toBe(false);
    expect(wrapper.find('input[type="file"]').exists()).toBe(true);
    await wrapper.get('[data-save]').trigger('click');
    expect(wrapper.emitted('submit')?.at(-1)).toEqual([
      { mobile: '13800000000', email: 'buyer@example.test', avatarAssetId: undefined },
    ]);
    expect(request.mock.calls.some(([call]) => call.path.includes('delete'))).toBe(false);
  } finally {
    wrapper.unmount();
  }
});

it('keeps an account without an employee compact and offers a single close action', async () => {
  const wrapper = mount(CurrentUserProfileDialog, {
    props: { open: true, profile: { username: 'admin' } },
    global: {
      stubs: { UiModal: { template: '<div><slot /><footer><slot name="operation" /></footer></div>' } },
    },
  });
  expect(wrapper.get('.profile-account-summary').text()).toContain('admin');
  expect(wrapper.text()).not.toContain('尚未绑定职员档案');
  expect(wrapper.findAll('footer button')).toHaveLength(1);
  await wrapper.get('footer button').trigger('click');
  expect(wrapper.emitted('close')).toHaveLength(1);
  wrapper.unmount();
});
