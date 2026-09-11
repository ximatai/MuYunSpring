import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { WebBusinessNotification } from '@muyun/web-contracts';
import BusinessNotificationPanel from '@/platform-components/BusinessNotificationPanel.vue';

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
      'required-2',
      'optional-1',
    ]);
    expect(wrapper.get('.business-notification-more').text()).toContain('还有 1 条');

    await wrapper.get('.business-notification-more').trigger('click');

    expect(wrapper.findAll('.business-notification-card')).toHaveLength(4);
    expect(wrapper.get('.business-notification-more').text()).toBe('收起提醒');
  });

  it('renders an optional presentation tone and occurrence time in the standard card header', () => {
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

    const cards = wrapper.findAll('.business-notification-card');
    expect(cards[0].classes()).toContain('business-notification-card--success');
    expect(cards[1].classes()).toContain('business-notification-card--danger');
    expect(cards[2].classes()).toContain('business-notification-card--default');
    expect(cards[0].get('.business-notification-time').attributes('datetime')).toBe(
      '2026-09-11T06:57:39.000Z',
    );
    expect(cards[1].find('.business-notification-time').exists()).toBe(false);
    expect(cards[0].find('.business-notification-status-dot').exists()).toBe(true);
  });
});
