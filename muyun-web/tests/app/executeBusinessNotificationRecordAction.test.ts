import { afterEach, expect, it, vi } from 'vitest';
import { executeBusinessNotificationRecordAction } from '@/app/executeBusinessNotificationRecordAction';
import { businessNotificationExpired } from '@muyun/web-core';
import type { WebBusinessNotification, WebBusinessNotificationRecordAction } from '@muyun/web-contracts';
const action: WebBusinessNotificationRecordAction = {
  kind: 'record',
  key: 'accept',
  label: '接听',
  moduleAlias: 'demo.call',
  recordId: 'call',
  actionCode: 'accept',
  confirmation: '确认接听？',
  dismissOnSuccess: false,
};
function notice(): WebBusinessNotification {
  return {
    id: 'call',
    code: 'call',
    title: '来电',
    content: '正文',
    dismissible: false,
    actions: [action],
    displayPolicy: 'persistent',
    expiresAt: '2026-10-21T00:00:01Z',
  };
}
afterEach(() => vi.useRealTimers());
it('rejects expired, invalid and exactly-due deadlines while preserving undated legacy notices', () => {
  const now = Date.parse('2026-10-21T00:00:01Z');
  expect(businessNotificationExpired(notice(), now)).toBe(true);
  expect(businessNotificationExpired({ expiresAt: 'invalid' }, now)).toBe(true);
  expect(businessNotificationExpired({ expiresAt: '' }, now)).toBe(true);
  expect(businessNotificationExpired({}, now)).toBe(false);
});
it('does not dispatch when confirmation outlives the deadline or the notification is removed', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-21T00:00:00Z'));
  let current: WebBusinessNotification | undefined = notice();
  const invoke = vi.fn(async () => undefined);
  let answer!: (value: boolean) => void;
  const confirm = () =>
    new Promise<boolean>((resolve) => {
      answer = resolve;
    });
  const pending = executeBusinessNotificationRecordAction(action, {
    current: () => current,
    confirm,
    invoke,
  });
  vi.setSystemTime(new Date('2026-10-21T00:00:01Z'));
  answer(true);
  expect(await pending).toBe(false);
  expect(invoke).not.toHaveBeenCalled();
  vi.setSystemTime(new Date('2026-10-21T00:00:00Z'));
  const removed = executeBusinessNotificationRecordAction(action, {
    current: () => current,
    confirm,
    invoke,
  });
  current = undefined;
  answer(true);
  expect(await removed).toBe(false);
  expect(invoke).not.toHaveBeenCalled();
});
it('dispatches only after confirmation and permits cancellation without treating it as completion', async () => {
  const current = { ...notice(), expiresAt: undefined };
  const invoke = vi.fn(async () => undefined);
  expect(
    await executeBusinessNotificationRecordAction(action, {
      current: () => current,
      confirm: () => false,
      invoke,
    }),
  ).toBe(false);
  expect(invoke).not.toHaveBeenCalled();
  expect(
    await executeBusinessNotificationRecordAction(action, {
      current: () => current,
      confirm: () => true,
      invoke,
    }),
  ).toBe(true);
  expect(invoke).toHaveBeenCalledOnce();
});

it.each([
  { recordId: 'replacement-call' },
  { payload: { invitation: 'replacement' } },
  { confirmation: '更新后的确认要求' },
])('rejects an action changed during confirmation: %j', async (change) => {
  let current = { ...notice(), expiresAt: undefined };
  const invoke = vi.fn(async () => undefined);
  const result = await executeBusinessNotificationRecordAction(action, {
    current: () => current,
    confirm: async () => {
      current = { ...current, actions: [{ ...action, ...change }] };
      return true;
    },
    invoke,
  });
  expect(result).toBe(false);
  expect(invoke).not.toHaveBeenCalled();
});
