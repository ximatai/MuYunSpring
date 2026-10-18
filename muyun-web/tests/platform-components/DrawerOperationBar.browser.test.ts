import { mount } from '@vue/test-utils';
import { h, ref } from 'vue';
import { expect, it } from 'vitest';
import { page } from 'vitest/browser';
import DrawerOperationBar from '@/platform-components/DrawerOperationBar.vue';
import AdaptiveHeaderActionBar, {
  type HeaderActionItem,
} from '@/platform-components/AdaptiveHeaderActionBar.vue';
import '@/styles.css';
import 'ant-design-vue/dist/reset.css';

it('restores drawer primary actions after a narrow view changes to editing or grows', async () => {
  await page.viewport(992, 814);
  const actions = ref<HeaderActionItem[]>([
    { key: 'long', title: 'A long business operation', level: 'standard' },
    { key: 'delete', title: 'Delete', level: 'secondary' },
  ]);
  const wrapper = mount(DrawerOperationBar, {
    attachTo: document.body,
    attrs: { style: 'width: 110px' },
    slots: { default: () => h(AdaptiveHeaderActionBar, { actions: actions.value }) },
  });
  const direct = () =>
    wrapper.findAll('.adaptive-header-action-bar__visible button').map((button) => button.text());
  try {
    await expect.poll(() => direct().includes('A long business operation')).toBe(false);
    actions.value = [
      { key: 'cancel', title: 'Cancel', level: 'standard' },
      { key: 'save', title: 'Save', level: 'primary' },
    ];
    await wrapper.vm.$nextTick();
    wrapper.element.style.width = '700px';
    await expect.poll(() => direct()).toEqual(['Cancel', 'Save']);
    wrapper.element.style.width = '90px';
    await expect.poll(() => direct().includes('Cancel')).toBe(false);
    wrapper.element.style.width = '700px';
    await expect.poll(() => direct()).toEqual(['Cancel', 'Save']);
    const region = wrapper.get('.drawer-operation-bar-actions').element;
    expect(region.getBoundingClientRect().width).toBeGreaterThan(600);
    expect(region.scrollWidth).toBeLessThanOrEqual(region.clientWidth + 1);
  } finally {
    wrapper.unmount();
  }
});
