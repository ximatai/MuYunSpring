import { flushPromises, shallowMount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import ModuleRuntimeActivationStatus from '@/views/ModuleRuntimeActivationStatus.vue';
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
  vi.clearAllMocks();
  can.mockReturnValue(true);
});
function mountStatus() {
  const wrapper = shallowMount(ModuleRuntimeActivationStatus, {
    props: { moduleAlias: 'sales.order' },
    global: {
      stubs: {
        UiButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
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
  expect(wrapper.text()).toContain('配置已提交，生效失败');
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试生效')!
    .trigger('click');
  await flushPromises();
  expect(request).toHaveBeenLastCalledWith({
    method: 'POST',
    path: '/platform.module/sales.order/runtime/activation/retry?expectedRevision=3',
  });
  expect(wrapper.text()).toContain('配置已生效');
  expect(wrapper.text()).not.toContain('重试生效');
});

it('does not infer this node is active from durable success and respects retry permission', async () => {
  can.mockReturnValue(false);
  request.mockResolvedValue({ ...state('ACTIVE'), installedRevision: null });
  const wrapper = mountStatus();
  await flushPromises();
  expect(wrapper.text()).toContain('当前节点尚未确认生效');
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
  expect(wrapper.text()).toContain('配置已生效');
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
  expect(wrapper.text()).toContain('配置已生效');
  expect(wrapper.text()).not.toContain('重试生效');
});
