import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { expect, it, vi } from 'vitest';
import { createLanguageContext, provideLanguageContext } from '@muyun/web-core';
import type { UserSessionView } from '@muyun/web-contracts';
import UserSessionExpandedSubtable from '@/platform-components/UserSessionExpandedSubtable.vue';
import { userSessionTerminalTitle } from '@/platform-components/userSessionPresentation';

it('projects session facts in the consumer language without changing identity or replaying actions', async () => {
  const language = createLanguageContext({ languageTag: 'en' });
  const revoke = vi.fn();
  const session: UserSessionView = {
    issuedAt: '2026-10-14T00:00:00Z',
    expiresAt: '2026-10-15T00:00:00Z',
    id: 'session-1',
    userId: 'user-1',
    username: '原姓名',
    current: true,
    present: true,
    presenceStatus: 'idle',
    presenceStatusTitle: '闲置',
    connectionCount: 2,
    terminalType: 'desktopWeb',
    terminalTypeTitle: 'Web 桌面端',
    platformType: 'other',
    platformTypeTitle: '其他系统',
    loginUserAgent: '原始 User-Agent',
  };
  const wrapper = mount(
    defineComponent({
      setup() {
        provideLanguageContext(language);
        return () =>
          h(UserSessionExpandedSubtable, {
            sessions: [session],
            canRevoke: () => true,
            onRevoke: revoke,
          });
      },
    }),
  );
  expect(wrapper.text()).toContain('Current session');
  expect(wrapper.text()).toContain('Desktop web / Other system');
  expect(wrapper.get('.user-session-expanded-presence').text()).toBe('Idle');
  expect(wrapper.get('.user-session-expanded-presence').attributes('title')).toBe(
    'Idle, live connections: 2',
  );
  expect(wrapper.text()).toContain('原始 User-Agent');
  language.setLanguage('zh-CN');
  await flushPromises();
  expect(wrapper.text()).toContain('Web 桌面端 / 其他系统');
  expect(wrapper.get('.user-session-expanded-presence').text()).toBe('闲置');
  expect(revoke).not.toHaveBeenCalled();
  language.setLanguage('en');
  await flushPromises();
  await wrapper
    .findAll('button')
    .find((button) => button.text() === 'Sign out session')!
    .trigger('click');
  expect(revoke).toHaveBeenCalledExactlyOnceWith(session);
  expect(session.terminalTypeTitle).toBe('Web 桌面端');
  // Future server types must retain their supplied label until this client owns the code.
  expect(
    userSessionTerminalTitle(
      {
        ...session,
        terminalType: 'future',
        terminalTypeTitle: 'Future terminal',
        platformType: 'future',
        platformTypeTitle: 'Future OS',
      },
      language,
    ),
  ).toBe('Future terminal / Future OS');
  wrapper.unmount();
});
