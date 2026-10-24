import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick } from 'vue';
import type { WebBusinessNotification } from '@muyun/web-contracts';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import BusinessNotificationPanel from '@/platform-components/BusinessNotificationPanel.vue';
import { registerUiBlockingOverlay } from '@/vue-ui-antdv/blockingOverlays';

function notification(
  id: string,
  dismissible = true,
  extra: Partial<WebBusinessNotification> = {},
): WebBusinessNotification {
  return { id, code: `demo.${id}`, title: id, content: '提醒正文', dismissible, actions: [], ...extra };
}
const command = {
  kind: 'record' as const,
  key: 'process',
  label: '处理',
  moduleAlias: 'demo.order',
  recordId: 'record',
  actionCode: 'approve',
  dismissOnSuccess: true,
};
function mountPanel(notifications: WebBusinessNotification[], executeAction = vi.fn()) {
  return mount(BusinessNotificationPanel, { props: { notifications, executeAction } });
}
afterEach(() => vi.useRealTimers());
describe('BusinessNotificationPanel', () => {
  it('renders receiver-language resources while executing the original declarative action', async () => {
    const language = createLanguageContext({ languageTag: 'en' });
    const action = {
      kind: 'record' as const,
      key: 'save',
      label: '处理',
      moduleAlias: 'demo.order',
      recordId: 'record',
      actionCode: 'approve',
      arguments: { opinion: '中文意见原文' },
      dismissOnSuccess: false,
    };
    const notice: WebBusinessNotification = {
      ...notification('notice', true),
      title: '待办原文',
      actions: [action],
      textResources: {
        title: {
          source: { sourceKind: 'CODE', sourceId: 'title', sourceVersion: 'v1', originalText: '待办原文' },
          languages: {
            en: { text: 'Pending task', requestedTag: 'en', missing: false, fallback: false },
          },
        },
        'action/save/label': {
          source: { sourceKind: 'CODE', sourceId: 'action', sourceVersion: 'v1', originalText: '处理' },
          languages: {
            en: { text: 'Process', requestedTag: 'en', missing: false, fallback: false },
          },
        },
      },
    };
    const executeAction = vi.fn();
    const host = defineComponent({
      setup() {
        provideLanguageContext(language);
        return () =>
          h(BusinessNotificationPanel, {
            notifications: [notification('second', true), notice],
            executeAction,
          });
      },
    });
    const wrapper = mount(host);
    expect(wrapper.text()).toContain('Pending task');
    expect(wrapper.get('.business-notification-bell').attributes('aria-label')).toBe('Session reminders (2)');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.text()).toContain('待办原文');
    expect(wrapper.get('.business-notification-bell').attributes('aria-label')).toBe('本次提醒（2 条）');
    language.setLanguage('en');
    await nextTick();
    const button = wrapper.findAll('button').find((item) => item.text() === 'Process');
    expect(button).toBeDefined();
    await button!.trigger('click');
    expect(executeAction).toHaveBeenCalledWith(notice, action);
    expect(action.arguments.opinion).toBe('中文意见原文');
    wrapper.unmount();
  });

  it.each(['success', 'danger', undefined] as const)(
    'keeps the original single card presentation and optional accessory (tone=%s)',
    (tone) => {
      const notice = notification('single', true, {
        tone,
        occurredAt: '2026-09-11T06:57:39Z',
        actions: [command, { ...command, key: 'later', label: '其他', placement: 'trailing' }],
      });
      const wrapper = mount(BusinessNotificationPanel, {
        props: { notifications: [notice], executeAction: vi.fn(), hasAccessory: () => true },
        slots: { accessory: '<span class="test-accessory">附件</span>' },
      });
      expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
      expect(wrapper.get('.business-notification-card').classes()).toContain(
        tone === 'danger' ? 'business-notification-card--danger' : 'business-notification-card--default',
      );
      expect(wrapper.get('.business-notification-action-region--trailing').text().replace(/\s/g, '')).toBe(
        '其他',
      );
      expect(wrapper.get('.test-accessory').text()).toBe('附件');
      expect(wrapper.get('time').attributes('datetime')).toBe('2026-09-11T06:57:39.000Z');
      expect(wrapper.find('.business-notification-controls').exists()).toBe(false);
      expect(wrapper.get('.business-notification-badge').text()).toBe('1');
    },
  );

  it('deduplicates the active collection and stacks the latest received card without overlapping bodies', () => {
    const wrapper = mountPanel([
      notification('required', false),
      notification('second'),
      notification('latest'),
      notification('latest', true, { title: '最新版本' }),
    ]);
    expect(wrapper.findAll('.business-notification-card h2').map((el) => el.text())).toEqual(['最新版本']);
    expect(wrapper.findAll('.business-notification-stack-edge')).toHaveLength(2);
    expect(wrapper.get('.business-notification-badge').text()).toBe('3');
    expect(wrapper.find('.business-notification-controls').exists()).toBe(false);
    expect(wrapper.get('.business-notification-bell').attributes('aria-label')).toBe('本次提醒（3 条）');
  });

  it('removes closed cards only from this session and suppresses repeated pushes of those IDs', async () => {
    const notices = [notification('one'), notification('two'), notification('three')];
    const execute = vi.fn();
    const wrapper = mountPanel(notices, execute);
    for (let i = 0; i < 3; i++) await wrapper.get('.business-notification-close').trigger('click');
    expect(wrapper.find('.business-notification-card').exists()).toBe(false);
    expect(wrapper.find('.business-notification-bell').exists()).toBe(false);
    expect(wrapper.emitted('dismiss')).toEqual([['three'], ['two'], ['one']]);
    expect(execute).not.toHaveBeenCalled();
    await wrapper.setProps({ notifications: [] });
    await wrapper.setProps({ notifications: [...notices] });
    expect(wrapper.find('.business-notification-card').exists()).toBe(false);
    await wrapper.setProps({ notifications: [...notices, notification('new')] });
    expect(wrapper.get('.business-notification-card h2').text()).toBe('new');
    expect(wrapper.get('.business-notification-badge').text()).toBe('1');
    wrapper.unmount();
    const reopened = mountPanel(notices);
    expect(reopened.get('.business-notification-badge').text()).toBe('3');
  });

  it('opens the temporary list through the bell and restores unclosed floating cards', async () => {
    const wrapper = mountPanel([notification('one', false), notification('two'), notification('three')]);
    await wrapper.get('.business-notification-close').trigger('click');
    await wrapper.get('.business-notification-bell').trigger('click');
    expect(wrapper.findAll('.business-notification-current .business-notification-card')).toHaveLength(2);
    expect(wrapper.find('.business-notification-current-header').exists()).toBe(false);
    expect(wrapper.get('.business-notification-bell').attributes('aria-expanded')).toBe('true');
    await wrapper.get('.business-notification-bell').trigger('click');
    expect(wrapper.get('.business-notification-card h2').text()).toBe('two');
    await wrapper.get('.business-notification-bell').trigger('click');
    expect(wrapper.findAll('.business-notification-current')).toHaveLength(1);
    expect(wrapper.find('.business-notification-session-note').exists()).toBe(false);
    expect(wrapper.get('.business-notification-bell').attributes('title')).toContain('刷新或退出后清空');
    expect(wrapper.find('.business-notification-stack').exists()).toBe(false);
  });

  it('removes business-completed reminders automatically while cancelled actions remain retryable', async () => {
    let finish!: (value: boolean) => void;
    const execute = vi.fn(
      () =>
        new Promise<boolean>((resolve) => {
          finish = resolve;
        }),
    );
    const notice = notification('task', false, {
      actions: [command, { ...command, key: 'other', label: '其他处理' }],
    });
    const wrapper = mount(BusinessNotificationPanel, {
      props: { notifications: [notice], executeAction: execute },
    });
    await wrapper.get('.business-notification-actions button').trigger('click');
    await wrapper.findAll('.business-notification-actions button')[1]!.trigger('click');
    expect(execute).toHaveBeenCalledTimes(1);
    finish(false);
    await flushPromises();
    expect(wrapper.get('.business-notification-badge').text()).toBe('1');
    await wrapper.get('.business-notification-actions button').trigger('click');
    await wrapper.setProps({ notifications: [{ ...notice }] });
    await wrapper.get('.business-notification-actions button').trigger('click');
    expect(execute).toHaveBeenCalledTimes(2);
    finish(true);
    await flushPromises();
    expect(wrapper.find('.business-notification-bell').exists()).toBe(false);
    expect(wrapper.emitted('dismiss')).toEqual([['task']]);
    await wrapper.setProps({ notifications: [{ ...notice }] });
    expect(wrapper.find('.business-notification-bell').exists()).toBe(false);
  });

  it('filters expiration at the deadline, invalid deadlines and domain withdrawals from every view', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-24T00:00:00Z'));
    const wrapper = mountPanel([
      notification('expires', true, { expiresAt: '2026-10-24T00:00:01Z' }),
      notification('withdraw'),
      notification('invalid', true, { expiresAt: 'invalid' }),
    ]);
    expect(wrapper.get('.business-notification-badge').text()).toBe('2');
    await wrapper.get('.business-notification-bell').trigger('click');
    await vi.advanceTimersByTimeAsync(1000);
    expect(wrapper.findAll('.business-notification-card h2').map((el) => el.text())).toEqual(['withdraw']);
    expect(wrapper.get('.business-notification-badge').text()).toBe('1');
    await wrapper.setProps({ notifications: [] });
    expect(wrapper.find('.business-notification-bell').exists()).toBe(false);
    expect(wrapper.find('.business-notification-current').exists()).toBe(false);
  });

  it('does not retire a replacement reminder when a stale action finishes', async () => {
    let finish!: (value: boolean) => void;
    const execute = vi.fn(
      () =>
        new Promise<boolean>((resolve) => {
          finish = resolve;
        }),
    );
    const original = notification('task', true, { actions: [command] });
    const wrapper = mount(BusinessNotificationPanel, {
      props: { notifications: [original], executeAction: execute },
    });
    await wrapper.get('.business-notification-actions button').trigger('click');
    await wrapper.setProps({
      notifications: [
        { ...original, title: '新的业务状态', actions: [{ ...command, arguments: { version: 2 } }] },
      ],
    });
    finish(true);
    await flushPromises();
    expect(wrapper.get('.business-notification-card h2').text()).toBe('新的业务状态');
    expect(wrapper.emitted('dismiss')).toBeUndefined();
    expect(wrapper.get('.business-notification-actions button').attributes('disabled')).toBeUndefined();
  });

  it('ignores completion from a withdrawn action without disturbing a newer invocation of the same ID', async () => {
    const completions: ((value: boolean) => void)[] = [];
    const executeAction = vi.fn(() => new Promise<boolean>((resolve) => completions.push(resolve)));
    const notice = notification('task', true, { actions: [command] });
    const wrapper = mountPanel([notice], executeAction);
    await wrapper.get('.business-notification-actions button').trigger('click');
    await wrapper.setProps({ notifications: [] });
    await wrapper.setProps({ notifications: [{ ...notice }] });
    await wrapper.get('.business-notification-actions button').trigger('click');
    expect(executeAction).toHaveBeenCalledTimes(2);
    completions[0]!(true);
    await flushPromises();
    expect(wrapper.emitted('dismiss')).toBeUndefined();
    expect(wrapper.get('.business-notification-actions button').attributes('disabled')).toBeDefined();
    completions[1]!(true);
    await flushPromises();
    expect(wrapper.emitted('dismiss')).toEqual([['task']]);
    expect(wrapper.find('.business-notification-bell').exists()).toBe(false);
  });

  it('does not dismiss a new host session when an unmounted action completes', async () => {
    let finish!: (value: boolean) => void;
    const executeAction = () =>
      new Promise<boolean>((resolve) => {
        finish = resolve;
      });
    const wrapper = mount(BusinessNotificationPanel, {
      props: { notifications: [notification('task', true, { actions: [command] })], executeAction },
    });
    await wrapper.get('.business-notification-actions button').trigger('click');
    wrapper.unmount();
    finish(true);
    await flushPromises();
    expect(wrapper.emitted('dismiss')).toBeUndefined();
  });

  it('preserves navigation after persistent commands without inferring business completion', async () => {
    const navigate = {
      kind: 'navigate' as const,
      key: 'view',
      label: '查看',
      moduleAlias: 'demo.call',
      dismissOnSuccess: false,
    };
    const notice = notification('call', false, {
      displayPolicy: 'persistent',
      actions: [{ ...command, dismissOnSuccess: false }, navigate],
    });
    const executeAction = vi.fn(async () => true);
    const wrapper = mount(BusinessNotificationPanel, { props: { notifications: [notice], executeAction } });
    const buttons = () => [
      ...document.querySelectorAll<HTMLButtonElement>('.business-notification-actions button'),
    ];
    buttons()[0]!.click();
    await flushPromises();
    expect(buttons().map((button) => button.disabled)).toEqual([true, false]);
    expect(wrapper.emitted('dismiss')).toBeUndefined();
    expect(document.querySelector('.business-notification-badge')?.textContent).toBe('1');
    wrapper.unmount();
  });

  it('keeps a handled persistent record action disabled after the same reminder is withdrawn and pushed again', async () => {
    const navigate = {
      kind: 'navigate' as const,
      key: 'view',
      label: '查看',
      moduleAlias: 'demo.call',
      dismissOnSuccess: false,
    };
    const notice = notification('call', false, {
      displayPolicy: 'persistent',
      actions: [{ ...command, dismissOnSuccess: false }, navigate],
    });
    const executeAction = vi.fn(async () => true);
    const wrapper = mount(BusinessNotificationPanel, { props: { notifications: [notice], executeAction } });
    const button = () => document.querySelector<HTMLButtonElement>('.business-notification-actions button');
    button()!.click();
    await flushPromises();
    expect(button()?.disabled).toBe(true);
    await wrapper.setProps({ notifications: [] });
    await wrapper.setProps({ notifications: [{ ...notice }] });
    expect(button()?.disabled).toBe(true);
    button()!.click();
    await flushPromises();
    expect(executeAction).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('defers ordinary cards for overlays but retains persistent presentation and the full active count', async () => {
    const overlay = registerUiBlockingOverlay();
    const wrapper = mountPanel([
      notification('persistent', false, { displayPolicy: 'persistent' }),
      notification('ordinary'),
    ]);
    try {
      overlay.setVisible(true);
      await nextTick();
      expect(document.querySelector('.business-notification-card h2')?.textContent).toBe('persistent');
      expect(document.querySelector('.business-notification-badge')?.textContent).toBe('2');
      overlay.release();
      await nextTick();
      expect(document.querySelector('.business-notification-card h2')?.textContent).toBe('ordinary');
      document.querySelector<HTMLButtonElement>('.business-notification-bell')!.click();
      await nextTick();
      overlay.setVisible(true);
      await nextTick();
      const cards = [
        ...document.querySelectorAll<HTMLElement>(
          '.business-notification-current .business-notification-card',
        ),
      ];
      expect(
        cards.find((card) => card.querySelector('h2')?.textContent === 'ordinary')?.parentElement?.hidden,
      ).toBe(true);
      expect(
        cards.find((card) => card.querySelector('h2')?.textContent === 'persistent')?.parentElement?.hidden,
      ).toBe(false);
    } finally {
      overlay.release();
      wrapper.unmount();
    }
  });
});
