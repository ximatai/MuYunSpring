import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import RecordActionBar from '@/platform-components/RecordActionBar.vue';

describe('RecordActionBar', () => {
  it('shows a disabled action reason from a hoverable wrapper', async () => {
    vi.useFakeTimers();
    const wrapper = mount(RecordActionBar, {
      attachTo: document.body,
      props: {
        context: {
          action: () => ({ available: true }),
        },
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
          action: () => ({ available: false, reason: '无权操作当前记录' }),
          recordActions: () => Promise.resolve({}),
        },
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
});
