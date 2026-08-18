import type {
  WebActionResultEnvelope,
  WebBusinessNotificationRecordAction,
} from '@muyun/web-contracts';
import type { HttpClient } from './http';

/** Calls the record endpoint declared by a notification, never a business-supplied arbitrary URL. */
export function invokeBusinessNotificationRecordAction(
  http: HttpClient,
  action: WebBusinessNotificationRecordAction,
) {
  return http.request<unknown | WebActionResultEnvelope<unknown>>({
    method: 'POST',
    path: `/${encodeURIComponent(action.moduleAlias)}/${encodeURIComponent(action.recordId)}/${action.endpoint}`,
    body: action.arguments ?? {},
  });
}
