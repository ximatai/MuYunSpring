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
        return () => h(BusinessNotificationPanel, { notifications: [notice], executeAction });
      },
    });
    const wrapper = mount(host);
    expect(wrapper.text()).toContain('Pending task');
    language.setLanguage('zh-CN');
    await nextTick();
    expect(wrapper.text()).toContain('待办原文');
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
