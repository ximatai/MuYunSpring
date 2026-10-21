import { businessNotificationExpired } from '@muyun/web-core';
import type { WebBusinessNotification, WebBusinessNotificationRecordAction } from '@muyun/web-contracts';

/** Confirmation may outlive the notification; check the current fact immediately before dispatch. */
export async function executeBusinessNotificationRecordAction(
  action: WebBusinessNotificationRecordAction,
  options: {
    current: () => WebBusinessNotification | undefined;
    confirm: () => boolean | Promise<boolean>;
    invoke: () => Promise<unknown>;
  },
): Promise<boolean> {
  const initial = options.current();
  if (!initial || businessNotificationExpired(initial)) return false;
  const signature = JSON.stringify(action);
  const matches = (notice: WebBusinessNotification) =>
    notice.actions.some((item) => item.kind === 'record' && JSON.stringify(item) === signature);
  if (!matches(initial)) return false;
  if (action.confirmation && !(await options.confirm())) return false;
  const current = options.current();
  if (!current || businessNotificationExpired(current)) return false;
  if (!matches(current)) return false;
  await options.invoke();
  return true;
}
