import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import type { WebBusinessNotification } from '@muyun/web-contracts';
import BusinessNotificationPanel from '@/platform-components/BusinessNotificationPanel.vue';
import { defineComponent, h, nextTick } from 'vue';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';

function notification(
  id: string,
  dismissible: boolean,
  presentation: Pick<WebBusinessNotification, 'tone' | 'occurredAt'> = {},
): WebBusinessNotification {
  return {
    id,
    code: `demo.${id}`,
    title: id,
    content: '提醒正文',
    dismissible,
    actions: [],
    ...presentation,
  };
}

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
            notifications: [notice, notification('second', true)],
            executeAction,
          });
      },
    });
    const wrapper = mount(host);
    expect(wrapper.text()).toContain('Pending task');
    expect(wrapper.text()).toContain('Business reminders (2)');
    expect(wrapper.get('.business-notification-more').text()).toBe('View all (1 more)');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.text()).toContain('待办原文');
    expect(wrapper.text()).toContain('业务提醒（2 条）');
    expect(wrapper.get('.business-notification-more').text()).toContain('还有 1 条');
    language.setLanguage('en');
    await nextTick();
    const button = wrapper.findAll('button').find((item) => item.text() === 'Process');
    expect(button).toBeDefined();
    await button!.trigger('click');
    expect(executeAction).toHaveBeenCalledWith(notice, action);
    expect(action.arguments.opinion).toBe('中文意见原文');
    wrapper.unmount();
  });

  it('prioritizes non-dismissible reminders and lets the user expand the temporary queue', async () => {
    const wrapper = mount(BusinessNotificationPanel, {
      props: {
        notifications: [
          notification('optional-1', true),
          notification('required-1', false),
          notification('optional-2', true),
          notification('required-2', false),
        ],
        executeAction: () => undefined,
      },
    });

    expect(wrapper.findAll('.business-notification-card h2').map((node) => node.text())).toEqual([
      'required-1',
    ]);
    expect(wrapper.get('.business-notification-more').text()).toContain('还有 3 条');

    await wrapper.get('.business-notification-more').trigger('click');

    expect(wrapper.findAll('.business-notification-card')).toHaveLength(4);
    expect(wrapper.get('.business-notification-collapse').text()).toBe('收起提醒');
  });

  it('renders an optional presentation tone and occurrence time in the standard card header', async () => {
    const wrapper = mount(BusinessNotificationPanel, {
      props: {
        notifications: [
          notification('helmet-online', true, {
            tone: 'success',
            occurredAt: '2026-09-11T06:57:39Z',
          }),
          notification('helmet-offline', true, { tone: 'danger' }),
          notification('ordinary', true),
        ],
        executeAction: () => undefined,
      },
    });

    await wrapper.get('.business-notification-more').trigger('click');
    const cards = wrapper.findAll('.business-notification-card');
    expect(cards[0].classes()).toContain('business-notification-card--default');
    expect(cards[1].classes()).toContain('business-notification-card--danger');
    expect(cards[2].classes()).toContain('business-notification-card--default');
    expect(cards[0].get('.business-notification-time').attributes('datetime')).toBe(
      '2026-09-11T06:57:39.000Z',
    );
    expect(cards[1].find('.business-notification-time').exists()).toBe(false);
    expect(cards[0].find('.business-notification-status-dot').exists()).toBe(true);
  });

  it('renders a consumer-provided accessory only for opted-in notifications', async () => {
    const wrapper = mount(BusinessNotificationPanel, {
      props: {
        notifications: [notification('helmet-online', true), notification('ordinary', true)],
        hasAccessory: (item: WebBusinessNotification) => item.id === 'helmet-online',
        executeAction: () => undefined,
      },
      slots: {
        accessory: '<div class="notification-test-accessory">小地图</div>',
      },
    });

    await wrapper.get('.business-notification-more').trigger('click');
    const cards = wrapper.findAll('.business-notification-card');
    expect(cards[0].classes()).toContain('business-notification-card--with-accessory');
    expect(cards[0].get('.notification-test-accessory').text()).toBe('小地图');
    expect(cards[1].find('.business-notification-accessory').exists()).toBe(false);
  });
  it('keeps collapsed presentation across new and empty queues, retaining non-dismissible reminders and their actions', async () => {
    const required = notification('required', false);
    required.actions = [
      {
        kind: 'navigate',
        key: 'todo',
        label: '办理待办',
        moduleAlias: 'iam.workflow_workbench',
        dismissOnSuccess: false,
      },
    ];
    const executeAction = vi.fn();
    const wrapper = mount(BusinessNotificationPanel, { props: { notifications: [required], executeAction } });
    expect(wrapper.findAll('.business-notification-card')).toHaveLength(1);
    await wrapper.get('.business-notification-collapse').trigger('click');
    expect(wrapper.findAll('.business-notification-card')).toHaveLength(0);
    expect(wrapper.get('.business-notification-summary').text()).toBe('业务提醒（1 条）');
    expect(wrapper.emitted('dismiss')).toBeUndefined();
    await wrapper.setProps({ notifications: [required, notification('new', true)] });
    expect(wrapper.findAll('.business-notification-card')).toHaveLength(0);
    expect(wrapper.get('.business-notification-summary').text()).toBe('业务提醒（2 条）');
    await wrapper.setProps({ notifications: [] });
    expect(wrapper.find('.business-notification-panel').exists()).toBe(false);
    await wrapper.setProps({ notifications: [required, notification('new', true)] });
    expect(wrapper.findAll('.business-notification-card')).toHaveLength(0);
    await wrapper.get('.business-notification-summary').trigger('click');
    expect(wrapper.findAll('.business-notification-card h2').map((item) => item.text())).toEqual([
      'required',
      'new',
    ]);
    expect(wrapper.findAll('.business-notification-close')).toHaveLength(1);
    await wrapper.findComponent({ name: 'UiActionButton' }).trigger('click');
    expect(executeAction).toHaveBeenCalledExactlyOnceWith(required, required.actions[0]);
    expect(wrapper.emitted('dismiss')).toBeUndefined();
    await wrapper.get('.business-notification-collapse').trigger('click');
    expect(wrapper.findAll('.business-notification-card')).toHaveLength(0);
  });
});

it('keeps all persistent cards outside ordinary sorting, collapse and overlay suppression, including accessories', async () => {
  const { registerUiBlockingOverlay } = await import('@/vue-ui-antdv/blockingOverlays');
  const overlay = registerUiBlockingOverlay();
  const ordinary = notification('ordinary-required', false);
  const urgent = { ...notification('invitation', true), displayPolicy: 'persistent' as const };
  const second = { ...notification('call', false), displayPolicy: 'persistent' as const };
  const wrapper = mount(BusinessNotificationPanel, {
    attachTo: document.body,
    props: { notifications: [ordinary], executeAction: vi.fn(), hasAccessory: () => true },
    slots: { accessory: '<span class="persistent-accessory">附件</span>' },
  });
  const titles = () =>
    [...document.querySelectorAll('.business-notification-card h2')].map((el) => el.textContent);
  try {
    await wrapper.get('.business-notification-collapse').trigger('click');
    await wrapper.setProps({ notifications: [ordinary, urgent, second] });
    expect(titles()).toEqual(['invitation', 'call']);
    expect(document.querySelectorAll('.persistent-accessory')).toHaveLength(2);
    overlay.setVisible(true);
    await nextTick();
    expect(titles()).toEqual(['invitation', 'call']);
    expect(document.querySelector('.business-notification-panel')!.hasAttribute('hidden')).toBe(false);
    expect(document.querySelector('.business-notification-summary')!.hasAttribute('hidden')).toBe(true);
    overlay.release();
    await nextTick();
    expect(document.querySelector('.business-notification-summary')?.textContent).toContain('1 条');
  } finally {
    wrapper.unmount();
    overlay.release();
  }
});

it('prevents competing or repeated completed persistent actions and permits retry after cancellation', async () => {
  const action = {
    kind: 'record' as const,
    key: 'accept',
    label: '接听',
    moduleAlias: 'demo.call',
    recordId: 'call',
    actionCode: 'accept',
    dismissOnSuccess: false,
  };
  const notice = {
    ...notification('call', false),
    displayPolicy: 'persistent' as const,
    actions: [action, { ...action, key: 'decline', label: '拒绝' }],
  };
  let finish!: (result: boolean) => void;
  const executeAction = vi.fn(
    () =>
      new Promise<boolean>((resolve) => {
        finish = resolve;
      }),
  );
  const wrapper = mount(BusinessNotificationPanel, {
    attachTo: document.body,
    props: { notifications: [notice], executeAction },
  });
  const buttons = () => [
    ...document.querySelectorAll<HTMLButtonElement>('.business-notification-actions button'),
  ];
  try {
    buttons()[0].click();
    buttons()[1].click();
    await nextTick();
    expect(executeAction).toHaveBeenCalledTimes(1);
    finish(false);
    await Promise.resolve();
    await nextTick();
    buttons()[0].click();
    await nextTick();
    expect(executeAction).toHaveBeenCalledTimes(2);
    finish(true);
    await Promise.resolve();
    await nextTick();
    expect(buttons().every((button) => button.disabled)).toBe(true);
    buttons()[0].click();
    buttons()[1].click();
    expect(executeAction).toHaveBeenCalledTimes(2);
  } finally {
    wrapper.unmount();
  }
});

it('disables actions at the deadline without waiting for another notification or overlay event', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-21T00:00:00Z'));
  const executeAction = vi.fn();
  const wrapper = mount(BusinessNotificationPanel, {
    attachTo: document.body,
    props: {
      notifications: [
        {
          ...notification('call', false),
          displayPolicy: 'persistent',
          expiresAt: '2026-10-21T00:00:01Z',
          actions: [
            {
              kind: 'navigate',
              key: 'view',
              label: '查看来电',
              moduleAlias: 'demo.call',
              dismissOnSuccess: false,
            },
          ],
        },
      ],
      executeAction,
    },
  });
  try {
    const button = document.querySelector<HTMLButtonElement>('.business-notification-actions button')!;
    expect(button.disabled).toBe(false);
    await vi.advanceTimersByTimeAsync(1000);
    expect(button.disabled).toBe(true);
    expect(document.querySelector('.business-notification-expired')?.textContent).toBe('已过期');
    button.click();
    expect(executeAction).not.toHaveBeenCalled();
  } finally {
    wrapper.unmount();
    vi.useRealTimers();
  }
});

it('allows a persistent notification to run while an unrelated ordinary action is pending', async () => {
  const action = (key: string) => ({
    kind: 'record' as const,
    key,
    label: key,
    moduleAlias: 'demo.call',
    recordId: key,
    actionCode: 'accept',
    dismissOnSuccess: false,
  });
  const ordinary = { ...notification('ordinary', false), actions: [action('ordinary-action')] };
  const urgent = {
    ...notification('urgent', false),
    displayPolicy: 'persistent' as const,
    actions: [action('urgent-action')],
  };
  let finish!: () => void;
  const executeAction = vi.fn((notice: WebBusinessNotification) =>
    notice.id === 'ordinary'
      ? new Promise<void>((resolve) => {
          finish = resolve;
        })
      : undefined,
  );
  const wrapper = mount(BusinessNotificationPanel, {
    attachTo: document.body,
    props: { notifications: [ordinary, urgent], executeAction },
  });
  try {
    const buttons = [
      ...document.querySelectorAll<HTMLButtonElement>('.business-notification-actions button'),
    ];
    buttons.find((button) => button.textContent?.trim() === 'ordinary-action')!.click();
    await nextTick();
    const urgentButton = buttons.find((button) => button.textContent?.trim() === 'urgent-action')!;
    expect(urgentButton.disabled).toBe(false);
    urgentButton.click();
    await nextTick();
    expect(executeAction.mock.calls.map(([notice]) => notice.id)).toEqual(['ordinary', 'urgent']);
  } finally {
    finish?.();
    wrapper.unmount();
  }
});

it('keeps navigation available after a persistent record command completes without allowing another command', async () => {
  const command = {
    kind: 'record' as const,
    key: 'accept',
    label: '接受',
    moduleAlias: 'demo.call',
    recordId: 'call',
    actionCode: 'accept',
    dismissOnSuccess: false,
  };
  const navigate = {
    kind: 'navigate' as const,
    key: 'view',
    label: '查看详情',
    moduleAlias: 'demo.call',
    recordId: 'call',
    dismissOnSuccess: false,
  };
  const executeAction = vi.fn(async () => true);
  const wrapper = mount(BusinessNotificationPanel, {
    attachTo: document.body,
    props: {
      notifications: [
        {
          ...notification('call', false),
          displayPolicy: 'persistent',
          actions: [command, { ...command, key: 'decline', label: '拒绝' }, navigate],
        },
      ],
      executeAction,
    },
  });
  const buttons = () => [
    ...document.querySelectorAll<HTMLButtonElement>('.business-notification-actions button'),
  ];
  try {
    buttons()[0].click();
    await Promise.resolve();
    await nextTick();
    expect(buttons().map((button) => button.disabled)).toEqual([true, true, false]);
    buttons()[0].click();
    buttons()[1].click();
    buttons()[2].click();
    await Promise.resolve();
    await nextTick();
    expect(executeAction.mock.calls).toHaveLength(2);
    expect(wrapper.emitted('dismiss')).toBeUndefined();
    expect(buttons()[2].disabled).toBe(false);
  } finally {
    wrapper.unmount();
  }
});
