import type {
  WebActionResultEnvelope,
  WebBusinessNotification,
  WebBusinessNotificationRecordAction,
} from '@muyun/web-contracts';
import type { HttpClient } from './http';

/** Calls the standard record action declared by a notification. */
export function invokeBusinessNotificationRecordAction(
  http: HttpClient,
  action: WebBusinessNotificationRecordAction,
) {
  return http.request<unknown | WebActionResultEnvelope<unknown>>({
    method: 'POST',
    path: `/${encodeURIComponent(action.moduleAlias)}/${encodeURIComponent(action.actionCode)}/${encodeURIComponent(action.recordId)}`,
    body: { payload: action.arguments ?? {} },
  });
}

/** Invalid supplied deadlines fail closed; an omitted deadline preserves legacy actions. */
export function businessNotificationExpired(
  notification: Pick<WebBusinessNotification, 'expiresAt'>,
  now = Date.now(),
) {
  if (notification.expiresAt == null) return false;
  const deadline = Date.parse(notification.expiresAt);
  return !Number.isFinite(deadline) || deadline <= now;
}
