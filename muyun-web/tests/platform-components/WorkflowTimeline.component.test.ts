import { mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import WorkflowTimeline from '@/platform-components/WorkflowTimeline.vue';

it('shows business reasons by default and expands the full audit on request', async () => {
  const wrapper = mount(WorkflowTimeline, {
    props: {
      nodes: [
        { nodeKey: 'review', nodeType: 'approval', id: 'node', nodeTitle: '采购审批' },
        { nodeKey: 'large', nodeType: 'approval', id: 'definition-node', title: '大额采购复核' },
      ],
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
        {
          id: 'large-approved',
          eventType: 'task_completed',
          nodeKey: 'large',
          nodeInstanceId: 'runtime-node',
        },
        { id: 'task-linked', eventType: 'task_completed', taskId: 'arrival' },
        { id: 'finished', eventType: 'instance_completed', actionCode: 'complete' },
      ],
      tasks: [{ id: 'arrival', taskKind: 'task', taskStatus: 'completed', nodeInstanceId: 'node' }],
    },
  });
  expect(wrapper.text()).toContain('采购审批');
  expect(wrapper.text()).toContain('张三');
  expect(wrapper.text()).toContain('同意采购');
  expect(wrapper.text()).toContain('大额采购复核');
  expect(wrapper.findAll('li').filter((item) => item.text().includes('采购审批'))).toHaveLength(2);
  expect(wrapper.text()).toContain('流程完成');
  expect(wrapper.text()).not.toContain('节点已激活');
  await wrapper.findComponent({ name: 'UiCheckbox' }).vm.$emit('update:checked', true);
  expect(wrapper.text()).toContain('节点已激活');
  expect(wrapper.text()).toContain('workflow task completed');
  wrapper.unmount();
});
