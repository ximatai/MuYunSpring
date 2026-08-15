import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import type { ModuleContext } from '@muyun/web-core';
import RecordActionBar from '@/platform-components/RecordActionBar.vue';

describe('RecordActionBar', () => {
  it('shows a disabled action reason from a hoverable wrapper', async () => {
    vi.useFakeTimers();
    const wrapper = mount(RecordActionBar, {
      attachTo: document.body,
      props: {
        context: {
          action: (actionCode: string) => ({ actionCode, available: true }),
        } as unknown as ModuleContext<unknown>,
        actions: [
          {
            key: 'agent-chat',
            title: '模拟问答',
            disabled: true,
            disabledReason: '请先选择目录',
          },
        ],
      },
    });

    const trigger = wrapper.find('.record-action-tooltip-trigger');
    expect(trigger.exists()).toBe(true);
    expect(trigger.find('button').attributes('disabled')).toBeDefined();

    await trigger.trigger('mouseenter');
    await vi.advanceTimersByTimeAsync(150);

    expect(document.body.textContent).toContain('请先选择目录');
    vi.useRealTimers();
  });

  it('shows the runtime authorization reason when an action has no extension-specific reason', async () => {
    vi.useFakeTimers();
    const wrapper = mount(RecordActionBar, {
      attachTo: document.body,
      props: {
        context: {
          action: (actionCode: string) => ({ actionCode, available: false, reason: '无权操作当前记录' }),
          recordActions: (recordId: string) => Promise.resolve({ recordId, actions: [] }),
        } as unknown as ModuleContext<unknown>,
        recordId: 'knowledge-file-1',
        actions: [{ key: 'delete', actionCode: 'delete', title: '删除' }],
      },
    });

    const trigger = wrapper.find('.record-action-tooltip-trigger');
    expect(trigger.find('button').attributes('disabled')).toBeDefined();

    await trigger.trigger('mouseenter');
    await vi.advanceTimersByTimeAsync(150);

    expect(document.body.textContent).toContain('无权操作当前记录');
    vi.useRealTimers();
  });

  it('keeps record actions disabled while their availability is being checked', async () => {
    let resolveAvailability: ((value: { recordId: string; actions: [] }) => void) | undefined;
    const wrapper = mount(RecordActionBar, {
      attachTo: document.body,
      props: {
        context: {
          action: (actionCode: string) => ({ actionCode, available: true }),
          recordActions: () =>
            new Promise((resolve) => {
              resolveAvailability = resolve;
            }),
        } as unknown as ModuleContext<unknown>,
        recordId: 'platform',
        actions: [{ key: 'delete', actionCode: 'delete', title: '删除' }],
      },
    });

    await wrapper.vm.$nextTick();
    expect(wrapper.find('button').attributes('disabled')).toBeDefined();

    resolveAvailability?.({ recordId: 'platform', actions: [] });
    await flushPromises();

    expect(wrapper.find('button').attributes('disabled')).toBeUndefined();
  });
});
