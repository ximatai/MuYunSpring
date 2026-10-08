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
