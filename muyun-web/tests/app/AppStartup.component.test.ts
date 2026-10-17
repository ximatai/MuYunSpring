import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent, h } from 'vue';
import App from '@/App.vue';
import { router } from '@/app/router';
import { AppError } from '@/web-core';
import { configureWorkspaceViewContributions } from '@/platform-workbench/workspaceViews';

const loadStartup = vi.hoisted(() => vi.fn());
vi.mock('@/app/appWorkbenchStartup', () => ({
  usesMockStartup: () => true,
  loadAppWorkbenchStartupState: loadStartup,
}));

it('renders a restored workspace even when the browser query order differs from its canonical address', async () => {
  const view = {
    type: 'acceptance.preview',
    route: '/_platform/workspace/acceptance.preview',
    moduleAlias: 'platform.module',
    presentations: ['tab'] as const,
    layout: 'workspace' as const,
    component: defineComponent({
      props: ['recordId'],
      setup: (props) => () => h('p', `Restored record ${props.recordId}`),
    }),
    titleOf: () => 'Acceptance preview',
    parse: (query: Record<string, unknown>) =>
      typeof query.recordId === 'string' ? { recordId: query.recordId } : undefined,
  };
  configureWorkspaceViewContributions('startup-query-order-test', [view]);
  loadStartup.mockReset().mockResolvedValue({
    session: { currentUser: { userId: 'route-order-user', system: true } },
    menus: [],
    tabs: [],
  });
  const pinia = createPinia();
  setActivePinia(pinia);
  await router.push(
    '/_platform/workspace/acceptance.preview?workspaceView=acceptance.preview&workspacePresentation=tab&recordId=one',
  );
  const wrapper = mount(App, { global: { plugins: [pinia, router] } });
  try {
    await flushPromises();
    expect(wrapper.text()).toContain('Restored record one');
    await router.push(
      '/_platform/workspace/acceptance.preview?recordId=two&workspacePresentation=tab&workspaceView=acceptance.preview',
    );
    await flushPromises();
    expect(wrapper.text()).toContain('Restored record two');
  } finally {
    wrapper.unmount();
    configureWorkspaceViewContributions('startup-query-order-test', []);
    await router.replace('/');
  }
});

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
