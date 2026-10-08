import { mount } from '@vue/test-utils';
import { it, expect, vi } from 'vitest';
import WorkflowParticipantEditor from '@/platform-components/WorkflowParticipantEditor.vue';
import type { HttpClient } from '@muyun/web-core';
it('requires an explicit choice of participants and never defaults to self approval', async () => {
  const wrapper = mount(WorkflowParticipantEditor, {
    props: { http: { request: vi.fn(async () => ({ records: [] })) } as HttpClient, value: '{"rules":[]}' },
    global: { stubs: { RecordMultiPicker: true } },
  });
  const button = wrapper.findAll('button').find((button) => button.text() === '添加人员规则')!;
  await button.trigger('click');
  const rule = JSON.parse(String(wrapper.emitted('update:value')?.at(-1)?.[0])).rules[0];
  expect(rule).toEqual({ type: 'USER', ids: [] });
  expect(wrapper.text()).not.toContain('当前包含提交人本人');
  wrapper.unmount();
});

it.each(['DEPARTMENT', 'DEPARTMENT_MANAGER', 'ORGANIZATION', 'ORGANIZATION_MANAGER'])(
  'uses the current scope when switching from supervisor to %s',
  async (relation) => {
    const wrapper = mount(WorkflowParticipantEditor, {
      props: {
        http: { request: vi.fn(async () => ({ records: [] })) } as HttpClient,
        value: '{"rules":[{"type":"RELATIVE","relation":"SUPERVISOR","depth":3}]}',
      },
      global: { stubs: { RecordMultiPicker: true } },
    });
    const selector = wrapper
      .findAllComponents({ name: 'UiSelect' })
      .find((item) => item.props('value') === 'SUPERVISOR')!;
    selector.vm.$emit('update:value', relation);
    await wrapper.vm.$nextTick();
    expect(JSON.parse(String(wrapper.emitted('update:value')?.at(-1)?.[0])).rules[0]).toMatchObject({
      relation,
      depth: 0,
    });
    expect(wrapper.text()).not.toContain('上级层数');
    selector.vm.$emit('update:value', 'SUPERVISOR');
    await wrapper.vm.$nextTick();
    expect(JSON.parse(String(wrapper.emitted('update:value')?.at(-1)?.[0])).rules[0]).toMatchObject({
      relation: 'SUPERVISOR',
      depth: 1,
    });
  },
);

it('shows and edits a persisted department manager ancestor depth without resetting the rule', async () => {
  const wrapper = mount(WorkflowParticipantEditor, {
    props: {
      http: { request: vi.fn(async () => ({ records: [] })) } as HttpClient,
      value: '{"rules":[{"type":"RELATIVE","relation":"DEPARTMENT_MANAGER","depth":1}]}',
    },
    global: { stubs: { RecordMultiPicker: true } },
  });
  expect(wrapper.text()).toContain('部门/组织上溯层数');
  expect(wrapper.text()).toContain('0 为当前部门/组织，1 为上一级');
  const depth = wrapper.findComponent({ name: 'UiInput' });
  expect(depth.props('value')).toBe(1);
  depth.vm.$emit('update:value', '2');
  await wrapper.vm.$nextTick();
  expect(JSON.parse(String(wrapper.emitted('update:value')?.at(-1)?.[0])).rules[0]).toMatchObject({
    relation: 'DEPARTMENT_MANAGER',
    depth: 2,
  });
});
