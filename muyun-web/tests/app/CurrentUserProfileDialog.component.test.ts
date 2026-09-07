import { flushPromises, shallowMount } from '@vue/test-utils';
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
