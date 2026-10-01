import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import Workbench from '@/platform-workbench/Workbench.vue';

it('lets the owner retry a failed startup without opening a page or replaying an assistant request', async () => {
  const wrapper = mount(Workbench, { props: { error: '服务暂时无法连接' } });
  const retry = wrapper.findAll('button').find((button) => button.text() === '重试加载');
  expect(retry).toBeDefined();
  await retry!.trigger('click');
  expect(wrapper.emitted('retryLoad')).toHaveLength(1);
  expect(wrapper.emitted('selectMenu')).toBeUndefined();
  await wrapper.setProps({ loading: true });
  expect(wrapper.text()).not.toContain('重试加载');
  await wrapper.setProps({ loading: false, error: undefined });
  expect(wrapper.text()).not.toContain('重试加载');
  wrapper.unmount();
});
