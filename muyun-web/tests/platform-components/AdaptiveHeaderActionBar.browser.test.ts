import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import AdaptiveHeaderActionBar from '@/platform-components/AdaptiveHeaderActionBar.vue';

it('measures one live control and preserves its primary neighbor while resizing', async () => {
  const width = ref(700);
  const controlWidth = ref(32);
  let mounts = 0;
  const component = defineComponent({
    setup() {
      mounts += 1;
      return () => h('button', { style: { width: `${controlWidth.value}px`, boxSizing: 'border-box' } }, 'H');
    },
  });
  const control = {
    component,
    moduleAlias: 'demo.device',
    record: Object.freeze({ id: 'a' }),
    sessionKey: 1,
    scopeKey: {},
    active: true,
    refresh() {},
  };
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h('div', { class: 'drawer-operation-bar-actions', style: { width: `${width.value}px` } }, [
          h(AdaptiveHeaderActionBar, {
            actions: [
              { key: 'history', title: '查看历史记录' },
              { key: 'hold', title: 'H', placement: 'after-primary', control },
              { key: 'call', title: '现场连线', level: 'primary' },
            ],
          }),
        ]),
    }),
    { attachTo: document.body },
  );
  const titles = () =>
    wrapper
      .findAll('.adaptive-header-action-bar__visible button')
      .map((button) => button.text().replace(/\s/g, ''));
  try {
    await expect.poll(titles).toEqual(['现场连线', 'H', '查看历史记录']);
    width.value = 280;
    await expect.poll(titles).toEqual(['现场连线', 'H', '查看历史记录']);
    controlWidth.value = 150;
    await expect.poll(titles).toEqual(['现场连线', 'H', '更多']);
    controlWidth.value = 32;
    await expect.poll(titles).toEqual(['现场连线', 'H', '查看历史记录']);
    width.value = 100;
    await expect.poll(titles).toEqual(['现场连线', 'H', '更多']);
    expect(wrapper.get('.adaptive-header-action-bar__visible').element.scrollWidth).toBeLessThanOrEqual(100);
    expect(mounts).toBe(1);
  } finally {
    wrapper.unmount();
  }
});

it('collapses on resize, keeps the primary action, and restores actions when space returns', async () => {
  const width = ref(700);
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h('div', { class: 'drawer-operation-bar-actions', style: { width: `${width.value}px` } }, [
          h(AdaptiveHeaderActionBar, {
            actions: [
              { key: 'history', title: '查看历史记录' },
              { key: 'upgrade', title: '设备软件升级' },
              { key: 'call', title: '现场连线', level: 'primary' },
            ],
          }),
        ]),
    }),
    { attachTo: document.body },
  );
  const titles = () =>
    wrapper
      .findAll('.adaptive-header-action-bar__visible button')
      .map((button) => button.text().replace(/\s/g, ''));
  try {
    await expect.poll(titles).toEqual(['现场连线', '查看历史记录', '设备软件升级']);
    width.value = 190;
    await expect.poll(titles).toEqual(['现场连线', '更多']);
    const visible = wrapper.get('.adaptive-header-action-bar__visible').element;
    expect(visible.scrollWidth).toBeLessThanOrEqual(190);
    width.value = 700;
    await expect.poll(titles).toEqual(['现场连线', '查看历史记录', '设备软件升级']);
  } finally {
    wrapper.unmount();
  }
});

it('reserves room for platform-owned sibling controls instead of measuring the whole operation region', async () => {
  const wrapper = mount(
    defineComponent({
      setup: () => () =>
        h(
          'div',
          {
            class: 'drawer-operation-bar-actions',
            style: { display: 'flex', gap: '8px', width: '280px' },
          },
          [
            h('button', { style: { width: '40px', flexShrink: 0 } }, 'Open'),
            h(AdaptiveHeaderActionBar, {
              actions: [
                { key: 'history', title: '查看历史记录' },
                { key: 'upgrade', title: '设备软件升级' },
                { key: 'call', title: '现场连线', level: 'primary' },
              ],
            }),
          ],
        ),
    }),
    { attachTo: document.body },
  );
  try {
    await expect
      .poll(() =>
        wrapper
          .findAll('.adaptive-header-action-bar__visible button')
          .map((button) => button.text().replace(/\s/g, '')),
      )
      .toEqual(['现场连线', '更多']);
    expect(wrapper.element.scrollWidth).toBeLessThanOrEqual(280);
  } finally {
    wrapper.unmount();
  }
});
