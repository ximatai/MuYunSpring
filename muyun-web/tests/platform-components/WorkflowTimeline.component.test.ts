import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import WorkflowTimeline from '@/platform-components/WorkflowTimeline.vue';

it('shows business reasons by default and expands the full audit on request', async () => {
  const wrapper = mount(WorkflowTimeline, {
    props: {
      nodes: [{ nodeKey: 'review', nodeType: 'approval', id: 'node', nodeTitle: '采购审批' }],
      events: [
        {
          id: 'approved',
          eventType: 'task_completed',
          actionCode: 'approve',
          nodeInstanceId: 'node',
          operatorTitle: '张三',
          payloadText: '{"reason":"同意采购"}',
          message: 'workflow task completed',
        },
        { id: 'activated', eventType: 'node_activated', message: '节点已激活', payloadText: 'invalid json' },
      ],
    },
  });
  expect(wrapper.text()).toContain('采购审批');
  expect(wrapper.text()).toContain('张三');
  expect(wrapper.text()).toContain('同意采购');
  expect(wrapper.text()).not.toContain('节点已激活');
  await wrapper.findComponent({ name: 'UiCheckbox' }).vm.$emit('update:checked', true);
  expect(wrapper.text()).toContain('节点已激活');
  expect(wrapper.text()).toContain('workflow task completed');
  wrapper.unmount();
});
