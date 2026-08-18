import type { WebActionResultEnvelope, WebBusinessNotificationRecordAction } from '@muyun/web-contracts';
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
