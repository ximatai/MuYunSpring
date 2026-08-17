import type { WebActionResultEnvelope, WebBusinessNotificationCommandAction } from '@muyun/web-contracts';
import type { HttpClient } from './http';

export function invokeBusinessNotificationCommand(
  http: HttpClient,
  notificationId: string,
  action: WebBusinessNotificationCommandAction,
) {
  return http.request<unknown | WebActionResultEnvelope<unknown>>({
    method: 'POST',
    path: `/platform/notifications/commands/${encodeURIComponent(action.command)}`,
    body: {
      notificationId,
      actionKey: action.key,
      arguments: action.arguments ?? {},
    },
  });
}
