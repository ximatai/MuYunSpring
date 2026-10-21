import { mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import WorkflowDesignProperties from '@/platform-components/WorkflowDesignProperties.vue';
import type { WorkflowDesign } from '@muyun/web-contracts';

it('keeps frozen properties read-only and delivers draft patches to the graph owner', async () => {
  const design: WorkflowDesign = {
    nodes: [{ nodeKey: 'review', nodeType: 'approval', title: '采购审批' }],
    links: [],
  };
  const wrapper = mount(WorkflowDesignProperties, {
    props: {
      design,
      nodeKey: 'review',
      routeKey: '',
      editable: false,
      http: { request: vi.fn() },
      moduleAlias: 'demo.purchase',
      fields: [],
      catalog: { tasks: [], queries: [], generations: [], associations: [] },
      actions: [],
    },
    global: { stubs: { WorkflowParticipantEditor: true, WorkflowBusinessTaskEditor: true } },
  });
  const name = wrapper
    .findAllComponents({ name: 'UiInput' })
    .find((input) => input.props('value') === '采购审批')!;
  expect(name.props('disabled')).toBe(true);
  name.vm.$emit('update:value', '非法修改');
  expect(wrapper.emitted('update-node')).toBeUndefined();
  expect(design.nodes[0]!.title).toBe('采购审批');

  await wrapper.setProps({ editable: true });
  name.vm.$emit('update:value', '采购复核');
  expect(wrapper.emitted('update-node')).toEqual([[{ title: '采购复核' }]]);
  expect(design.nodes[0]!.title).toBe('采购审批');
  wrapper.unmount();
});

it('presents legacy notice nodes as FYI cards and resets incompatible settings in one owner patch', async () => {
  const original = {
    nodeKey: 'review',
    nodeType: 'approval',
    title: '采购知会',
    approvalMode: 'notice',
    participantPolicyText: '{"rules":[{"type":"INITIATOR_SELF"}]}',
  };
  const wrapper = mount(WorkflowDesignProperties, {
    props: {
      design: { nodes: [original], links: [] },
      nodeKey: 'review',
      routeKey: '',
      editable: true,
      http: { request: vi.fn() },
      moduleAlias: 'demo.purchase',
      fields: [],
      catalog: { tasks: [], queries: [], generations: [], associations: [] },
      actions: [],
    },
    global: { stubs: { WorkflowParticipantEditor: true, WorkflowBusinessTaskEditor: true } },
  });
  const cards = wrapper.findComponent({ name: 'UiRadioGroup' });
  expect(cards.props('appearance')).toBe('cards');
  expect(cards.props('value')).toBe('notice');
  expect(cards.props('options').map((option: { label: string }) => option.label)).toEqual([
    '审批',
    '知会',
    '业务任务',
    '里程碑',
  ]);
  expect(wrapper.text()).toContain('流程不会等待');
  expect(wrapper.text()).not.toContain('审批规则');
  expect(wrapper.findComponent({ name: 'WorkflowParticipantEditor' }).exists()).toBe(true);
  cards.vm.$emit('update:value', 'approval');
  const approval = wrapper.emitted('update-node')![0]![0] as Record<string, unknown>;
  expect(approval).toMatchObject({ nodeType: 'approval', approvalMode: 'all', allowReject: true });
  expect(approval).not.toHaveProperty('participantPolicyText');
  await wrapper.setProps({ design: { nodes: [{ ...original, ...approval }], links: [] } });
  cards.vm.$emit('update:value', 'notice');
  expect(wrapper.emitted('update-node')![1]![0]).toMatchObject({
    nodeType: 'approval',
    approvalMode: 'notice',
    allowReject: false,
    allowRollback: false,
    allowAddSign: false,
    approvalRatio: undefined,
    warningDurationMinutes: undefined,
  });
  await wrapper.setProps({ editable: false });
  cards.vm.$emit('update:value', 'task');
  expect(wrapper.emitted('update-node')).toHaveLength(2);
  expect(cards.props('disabled')).toBe(true);
  expect(original.approvalMode).toBe('notice');
  wrapper.unmount();
});
