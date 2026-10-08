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
