import { defineComponent, h, ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import { useAssistantAvailability } from '@/app/useAssistantAvailability';
import { appDataChangeDispatcher } from '@/platform-admin-runtime/realtime';

it('fails closed, rejects stale identity decisions, refreshes on focus and configuration changes, and aborts on disposal', async () => {
  const identity = ref<string>();
  const pending: Array<{
    resolve: (value: boolean) => void;
    reject: (error: Error) => void;
    signal: AbortSignal;
  }> = [];
  const load = vi.fn(
    (signal: AbortSignal) =>
      new Promise<boolean>((resolve, reject) => pending.push({ resolve, reject, signal })),
  );
  let state!: ReturnType<typeof useAssistantAvailability>;
  const wrapper = mount(
    defineComponent({
      setup() {
        state = useAssistantAvailability({ identity: () => identity.value, load });
        return () => h('div');
      },
    }),
  );
  try {
    expect(state.available.value).toBe(false);
    expect(load).not.toHaveBeenCalled();
    identity.value = 'user-a';
    expect(state.available.value).toBe(false);
    identity.value = 'user-b';
    expect(pending[0]!.signal.aborted).toBe(true);
    pending[0]!.resolve(true);
    pending[1]!.resolve(false);
    await flushPromises();
    expect(state.available.value).toBe(false);
    window.dispatchEvent(new Event('focus'));
    pending[2]!.resolve(true);
    await flushPromises();
    expect(state.available.value).toBe(true);
    const changed = appDataChangeDispatcher.dispatch({
      changeSetId: 'assistant-model-revoked',
      changes: [
        { type: 'record-updated', moduleAlias: 'platform.ai_model_configuration', recordId: 'config' },
      ],
    });
    await flushPromises();
    expect(load).toHaveBeenCalledTimes(4);
    pending[3]!.reject(new Error('offline'));
    await changed;
    await flushPromises();
    expect(state.available.value).toBe(false);
    const refresh = state.refresh();
    wrapper.unmount();
    expect(pending[4]!.signal.aborted).toBe(true);
    pending[4]!.resolve(true);
    await refresh;
    expect(state.available.value).toBe(false);
  } finally {
    wrapper.unmount();
  }
});
