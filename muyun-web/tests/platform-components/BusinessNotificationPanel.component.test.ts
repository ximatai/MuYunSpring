import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { WebBusinessNotification } from '@muyun/web-contracts';
import BusinessNotificationPanel from '@/platform-components/BusinessNotificationPanel.vue';

function notification(id: string, dismissible: boolean): WebBusinessNotification {
  return {
    id,
    code: `demo.${id}`,
    title: id,
    content: '提醒正文',
    dismissible,
    actions: [],
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
});
