import { mount } from '@vue/test-utils';
import { defineComponent, h, KeepAlive, ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import StaticRoutePageHost from '@/app/StaticRoutePageHost.vue';
import RecordDetailDrawer from '@/platform-components/RecordDetailDrawer.vue';
import '@/styles.css';
import '@muyun/vue-ui-antdv/styles.css';
import UiThemeProvider from '@/vue-ui-antdv/components/UiThemeProvider.vue';

it('anchors drawers outside page scrolling and isolates their DOM with cached tabs', async () => {
  await page.viewport(1920, 1080);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/first', component: { template: '<div />' } },
      { path: '/second', component: { template: '<div />' } },
    ],
  });
  await router.push('/first');
  const firstRoute = router.currentRoute.value;
  await router.push('/second');
  const secondRoute = router.currentRoute.value;
  const afterClose = vi.fn();
  const content = defineComponent({
    setup() {
      const open = ref(false);
      return () =>
        h('div', [
          h('button', { onClick: () => (open.value = true) }, '打开流程属性'),
          h('div', { style: 'height:1800px' }, '长页面内容'),
          h(
            RecordDetailDrawer,
            {
              open: open.value,
              title: '流程属性',
              width: 'extraWide',
              onClose: () => (open.value = false),
              onAfterClose: afterClose,
            },
            {
              default: () =>
                h('div', { style: 'height:1800px' }, [
                  h('input', { 'aria-label': '配置草稿' }),
                  '长属性内容',
                ]),
              operation: () => h('button', '完成配置'),
            },
          ),
        ]);
    },
  });
  const secondContent = defineComponent({ render: () => h('p', '第二页签') });
  const harness = defineComponent({
    setup() {
      const first = ref(true);
      return () =>
        h('div', [
          h('button', { onClick: () => (first.value = !first.value) }, '切换页签'),
          h('div', { class: 'workspace-fixture', style: 'height:900px;width:1600px' }, [
            h(KeepAlive, {}, () =>
              h(StaticRoutePageHost, {
                key: first.value ? 'first' : 'second',
                route: first.value ? firstRoute : secondRoute,
                component: first.value ? content : secondContent,
              }),
            ),
          ]),
        ]);
    },
  });
  const wrapper = mount(UiThemeProvider, {
    attachTo: document.body,
    slots: { default: () => h(harness) },
    global: { stubs: { transition: false, transitionGroup: false } },
  });
  await page.getByRole('button', { name: '打开流程属性' }).click();
  await expect.element(page.getByRole('heading', { name: '流程属性', exact: true })).toBeVisible();
  const host = wrapper.get('.ui-side-panel-host').element;
  const drawer = host.querySelector('.record-detail-layout--drawer')!;
  await expect
    .poll(() => Math.round(drawer.getBoundingClientRect().right))
    .toBe(Math.round(host.getBoundingClientRect().right));
  expect(drawer.getBoundingClientRect().top).toBe(host.getBoundingClientRect().top);
  expect(drawer.getBoundingClientRect().height).toBe(host.getBoundingClientRect().height);
  await page.getByRole('textbox', { name: '配置草稿' }).fill('未保存的流程规则');
  const workspace = wrapper.get('.workspace-fixture').element as HTMLElement;
  workspace.style.width = '640px';
  await expect.poll(() => Math.round(drawer.getBoundingClientRect().width)).toBe(640);
  await expect
    .poll(() => Math.round(drawer.getBoundingClientRect().left))
    .toBe(Math.round(host.getBoundingClientRect().left));
  workspace.style.width = '1600px';
  const scroll = wrapper.get('.static-route-page-content').element;
  scroll.scrollTop = 600;
  scroll.dispatchEvent(new Event('scroll'));
  expect(drawer.getBoundingClientRect().top).toBe(host.getBoundingClientRect().top);
  await expect.element(page.getByRole('button', { name: '完成配置', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '切换页签' }).click();
  await expect.element(page.getByText('第二页签', { exact: true })).toBeVisible();
  await expect.element(page.getByRole('heading', { name: '流程属性', exact: true })).not.toBeInTheDocument();
  await page.getByRole('button', { name: '切换页签' }).click();
  await expect.element(page.getByRole('heading', { name: '流程属性', exact: true })).toBeVisible();
  await expect.poll(() => wrapper.get('.static-route-page-content').element.scrollTop).toBe(600);
  expect(wrapper.get<HTMLInputElement>('[aria-label="配置草稿"]').element.value).toBe('未保存的流程规则');
  expect(afterClose).not.toHaveBeenCalled();
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  await expect.poll(() => afterClose.mock.calls.length).toBe(1);
  wrapper.unmount();
});
