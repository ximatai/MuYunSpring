import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import WorkbenchAssistantPanel from '@/platform-workbench/WorkbenchAssistantPanel.vue';
import {
  createAssistantSurfaceRegistry,
  type AssistantCapability,
  type AssistantTurnRequester,
} from '@muyun/web-core';

function createRegistry(requestTurn: AssistantTurnRequester) {
  return createRegistryWithCapabilities(requestTurn, []);
}

function createRegistryWithCapabilities(
  requestTurn: AssistantTurnRequester,
  capabilities: AssistantCapability[],
) {
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => capabilities,
      requestTurn,
    },
  });
  registry.activate('tab-a');
  return registry;
}

it('submits a user request and renders the final assistant response', async () => {
  const requestTurn = vi.fn(async () => ({ text: '已经找到对应页面', toolCalls: [] as never[] }));
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('打开客户管理');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(requestTurn).toHaveBeenCalledOnce();
  expect(wrapper.text()).toContain('打开客户管理');
  expect(wrapper.text()).toContain('已经找到对应页面');
});

it('cancels an in-flight request from the panel', async () => {
  const requestTurn = vi.fn(
    (_input, signal: AbortSignal) =>
      new Promise<{ toolCalls: never[] }>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('cancelled', 'AbortError')), {
          once: true,
        });
      }),
  );
  const wrapper = mount(WorkbenchAssistantPanel, {
    props: { open: true, registry: createRegistry(requestTurn) },
  });

  await wrapper.get('textarea').setValue('执行一个较慢的任务');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await wrapper.get('.assistant-panel__actions button').trigger('click');
  await flushPromises();

  expect(wrapper.text()).toContain('已停止本次操作');
});

it('treats an empty follow-up as completion after a successful page operation', async () => {
  const requestTurn = vi
    .fn()
    .mockResolvedValueOnce({ toolCalls: [{ id: 'call-1', code: 'page.inspect', input: {} }] })
    .mockResolvedValueOnce({ toolCalls: [] });
  const registry = createRegistryWithCapabilities(requestTurn, [
    {
      descriptor: { code: 'page.inspect', description: 'Inspect page', inputSchema: {} },
      parseInput: (input) => input,
      execute: async () => ({ inspected: true }),
    },
  ]);
  const wrapper = mount(WorkbenchAssistantPanel, { props: { open: true, registry } });

  await wrapper.get('textarea').setValue('检查当前页面');
  await wrapper.get('button.ant-btn-primary').trigger('click');
  await flushPromises();

  expect(wrapper.text()).toContain('已执行 1 项操作');
  expect(wrapper.text()).toContain('操作已完成，请检查当前页面');
});
