import { flushPromises, shallowMount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import ModuleRuntimeActivationStatus from '@/views/ModuleRuntimeActivationStatus.vue';
import { resolveWebActionResult } from '@/web-core';
import type { ModuleActivationFeedback } from '@/views/moduleRuntimeActivation';
import type { DynamicRuntimeActivationStatus } from '@/web-contracts';

const { request, can } = vi.hoisted(() => ({ request: vi.fn(), can: vi.fn(() => true) }));
vi.mock('@muyun/web-core', async (original) => ({
  ...(await original<typeof import('@muyun/web-core')>()),
  useModuleContext: () => ({ http: { request }, can }),
}));
const wrappers: ReturnType<typeof shallowMount>[] = [];
afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount());
  wrappers.length = 0;
  vi.useRealTimers();
  vi.resetAllMocks();
  can.mockReturnValue(true);
});
function mountStatus() {
  const wrapper = shallowMount(ModuleRuntimeActivationStatus, {
    props: { moduleAlias: 'sales.order' },
    global: {
      stubs: {
        RecordStatusTag: false,
        UiActionButton: {
          props: ['disabled', 'title'],
          template: '<button :disabled="disabled" :title="title"><slot /></button>',
        },
        UiPopover: {
          data: () => ({ open: false }),
          template:
            '<div><div @click="open = !open"><slot /></div><div v-if="open" role="dialog"><slot name="content" /></div></div>',
        },
      },
    },
  });
  wrappers.push(wrapper);
  return wrapper;
}
function state(
  status: DynamicRuntimeActivationStatus['status'],
  revision = 3,
): DynamicRuntimeActivationStatus {
  return {
    moduleAlias: 'sales.order',
    status,
    desiredRevision: revision,
    lastSuccessfulRevision: 2,
    installedRevision: status === 'ACTIVE' ? revision : null,
    failureMessage: null,
    attemptedAt: null,
  };
}

it('distinguishes committed failure from save failure and retries exactly the observed revision', async () => {
  request.mockResolvedValueOnce(state('FAILED')).mockResolvedValueOnce(state('ACTIVE'));
  const wrapper = mountStatus();
  await flushPromises();
  expect(wrapper.text()).toContain('生效失败');
  expect(wrapper.text()).toContain('修改已保存，但未能生效');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试生效')!
    .trigger('click');
  await flushPromises();
  expect(request).toHaveBeenLastCalledWith({
    method: 'POST',
    path: '/platform.module/sales.order/runtime/activation/retry?expectedRevision=3',
  });
  expect(wrapper.find('section').exists()).toBe(false);
  expect(wrapper.text()).not.toContain('重试生效');
});

it('does not infer this node is active from durable success and respects retry permission', async () => {
  can.mockReturnValue(false);
  request.mockResolvedValue({ ...state('ACTIVE'), installedRevision: null });
  const wrapper = mountStatus();
  await flushPromises();
  expect(wrapper.text()).toContain('正在应用修改');
  expect(wrapper.text()).not.toContain('已生效');
  expect(wrapper.text()).not.toContain('重试生效');
});

it('ignores responses from the previous module and refreshes after a stale retry rejection', async () => {
  let completeOld: (value: DynamicRuntimeActivationStatus) => void = () => {};
  request
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          completeOld = resolve;
        }),
    )
    .mockResolvedValueOnce(state('FAILED', 5));
  const wrapper = mountStatus();
  await wrapper.setProps({ moduleAlias: 'sales.invoice' });
  await flushPromises();
  completeOld(state('ACTIVE'));
  await flushPromises();
  expect(wrapper.text()).toContain('生效失败');
  request.mockRejectedValueOnce(new Error('stale revision')).mockResolvedValueOnce(state('ACTIVE', 6));
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试生效')!
    .trigger('click');
  await flushPromises();
  expect(request).toHaveBeenLastCalledWith({
    method: 'GET',
    path: '/platform.module/sales.invoice/runtime/activation',
  });
  expect(wrapper.find('section').exists()).toBe(false);
});

it('keeps retry progress local to the current module', async () => {
  const completions: Array<(value: DynamicRuntimeActivationStatus) => void> = [];
  request.mockImplementation((options: { method: string }) =>
    options.method === 'GET'
      ? Promise.resolve(state('FAILED'))
      : new Promise((resolve) => completions.push(resolve)),
  );
  const wrapper = mountStatus();
  const retryButton = () => wrapper.findAll('button').find((button) => button.text() === '重试生效')!;
  await flushPromises();
  await retryButton().trigger('click');
  expect(retryButton().element.disabled).toBe(true);
  await wrapper.setProps({ moduleAlias: 'sales.invoice' });
  await flushPromises();
  expect(retryButton().element.disabled).toBe(false);
  await retryButton().trigger('click');
  expect(completions).toHaveLength(2);
  completions[0](state('ACTIVE'));
  await flushPromises();
  expect(wrapper.text()).toContain('生效失败');
  expect(retryButton().element.disabled).toBe(true);
  completions[1](state('ACTIVE'));
  await flushPromises();
  expect(wrapper.find('section').exists()).toBe(false);
  expect(wrapper.text()).not.toContain('重试生效');
});

it('hides healthy status and reveals technical versions only in diagnostics when attention is needed', async () => {
  request.mockResolvedValueOnce(state('ACTIVE')).mockResolvedValueOnce(state('FAILED'));
  const wrapper = mountStatus();
  await flushPromises();
  expect(wrapper.find('section').exists()).toBe(false);
  await wrapper.setProps({ reloadKey: 1 });
  await flushPromises();
  expect(wrapper.text()).not.toContain('配置版本');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '查看原因')!
    .trigger('click');
  expect(wrapper.find('[role=dialog]').text()).toContain('配置版本3');
  expect(wrapper.find('[role=dialog]').text()).toContain('模块运行配置的生效情况');
});

it('automatically follows pending activation and dismisses the banner after success', async () => {
  vi.useFakeTimers();
  request.mockResolvedValueOnce(state('PENDING')).mockResolvedValue(state('ACTIVE'));
  const wrapper = mountStatus();
  await flushPromises();
  expect(wrapper.text()).toContain('正在应用修改');
  await vi.advanceTimersByTimeAsync(2000);
  await flushPromises();
  expect(wrapper.find('section').exists()).toBe(false);
  await vi.advanceTimersByTimeAsync(10000);
  expect(request).toHaveBeenCalledTimes(2);
});

it('stops pending checks on unmount and does not poll a failed activation', async () => {
  vi.useFakeTimers();
  request.mockResolvedValue(state('PENDING'));
  const wrapper = mountStatus();
  await flushPromises();
  wrapper.unmount();
  await vi.advanceTimersByTimeAsync(10000);
  expect(request).toHaveBeenCalledTimes(1);
  request.mockResolvedValue({ ...state('FAILED'), failureMessage: '字段类型不兼容' });
  const failed = mountStatus();
  await flushPromises();
  await vi.advanceTimersByTimeAsync(10000);
  expect(request).toHaveBeenCalledTimes(2);
  expect(failed.text()).not.toContain('字段类型不兼容');
  await failed
    .findAll('button')
    .find((button) => button.text() === '查看原因')!
    .trigger('click');
  expect(failed.text()).toContain('字段类型不兼容');
});

it('shows query failures inline and recovers through recheck without claiming activation failed', async () => {
  request.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(state('ACTIVE'));
  const wrapper = mountStatus();
  await flushPromises();
  expect(wrapper.text()).toContain('暂时无法查询');
  expect(wrapper.text()).not.toContain('生效失败');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重新查询')!
    .trigger('click');
  await flushPromises();
  expect(wrapper.find('section').exists()).toBe(false);
});

it.each([
  ['ACTIVE', 'SUCCESS'],
  ['PENDING', 'INFO'],
  ['FAILED', 'WARNING'],
] as const)(
  'returns accurate save feedback for %s without treating activation failure as save failure',
  async (status, type) => {
    request.mockResolvedValue(state(status));
    const wrapper = mountStatus();
    await flushPromises();
    const feedback = await (
      wrapper.vm as unknown as { refresh(): Promise<ModuleActivationFeedback> }
    ).refresh();
    expect(resolveWebActionResult(feedback).messageType).toBe(type);
    expect(feedback.message.text).toContain(status === 'ACTIVE' ? '已生效' : '已保存');
  },
);
