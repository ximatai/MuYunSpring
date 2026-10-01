import { mount } from '@vue/test-utils';
import { Modal } from 'ant-design-vue';
import { defineComponent, type VNode } from 'vue';
import { expect, it } from 'vitest';
import { createConfirmAction } from '@/vue-ui-antdv/confirm';

it('keeps the review summary visible and technical details collapsed without changing cancellation', async () => {
  let options!: Parameters<typeof Modal.confirm>[0];
  const confirm = createConfirmAction((value) => {
    options = value;
    return { destroy() {}, update() {} };
  });
  const result = confirm({
    title: '确认保存字段',
    content: '新增「姓名」：必填\n警告：需要刷新生效',
    details: { title: '查看详细配置和影响', lines: ['字段 title', '建立索引'] },
  });
  const wrapper = mount(defineComponent({ render: options.content as () => VNode }));
  expect(wrapper.get('p').text()).toContain('姓名');
  expect(wrapper.get('p').text()).toContain('需要刷新生效');
  expect(wrapper.get('details').attributes('open')).toBeUndefined();
  expect(wrapper.get('summary').text()).toBe('查看详细配置和影响');
  expect(wrapper.get('details').text()).toContain('建立索引');
  options.onCancel?.();
  expect(await result).toBe(false);
  wrapper.unmount();
});
