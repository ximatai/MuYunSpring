import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import AdaptiveHeaderActionBar from '@/platform-components/AdaptiveHeaderActionBar.vue';

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
