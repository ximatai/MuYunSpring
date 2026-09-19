import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import WorkbenchAssistantPanel from '@/platform-workbench/WorkbenchAssistantPanel.vue';
import { createAssistantSurfaceRegistry, type AssistantTurnRequester } from '@muyun/web-core';

function createRegistry(requestTurn: AssistantTurnRequester) {
  const registry = createAssistantSurfaceRegistry();
  registry.register({
    pageInstanceKey: 'tab-a',
    contextRevision: () => 'stable',
    surface: {
      describe: () => ({ surface: 'workbench', facts: {} }),
      capabilities: () => [],
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
