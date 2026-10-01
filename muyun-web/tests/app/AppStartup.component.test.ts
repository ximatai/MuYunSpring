import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { createPinia } from 'pinia';
import App from '@/App.vue';
import { router } from '@/app/router';
import { AppError } from '@/web-core';

const loadStartup = vi.hoisted(() => vi.fn());
vi.mock('@/app/appWorkbenchStartup', () => ({
  usesMockStartup: () => true,
  loadAppWorkbenchStartupState: loadStartup,
}));

it.each([
  [new Error('服务暂时未就绪'), '服务暂时未就绪'],
  [new AppError('Network request failed', { code: 'NETWORK_ERROR' }), '暂时无法连接服务'],
])('recovers after startup failure through the read-only loader: %s', async (failure, message) => {
  loadStartup
    .mockReset()
    .mockRejectedValueOnce(failure)
    .mockResolvedValueOnce({
      session: { currentUser: { userId: 'recovery-user', system: true } },
      menus: [],
      tabs: [],
    });
  const wrapper = mount(App, { global: { plugins: [createPinia(), router] } });
  await flushPromises();
  expect(wrapper.text()).toContain(message);
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试加载')!
    .trigger('click');
  await flushPromises();
  expect(loadStartup).toHaveBeenCalledTimes(2);
  expect(wrapper.text()).not.toContain(message);
  expect(wrapper.text()).not.toContain('重试加载');
  expect(wrapper.text()).toContain('暂无打开页面');
  wrapper.unmount();
});
