import type { HttpClient } from '@muyun/web-core';
import type { RecordReferencePickerSource } from './recordReferencePickerProvider';

/** Paged delivery retains the IAM selector's own authorization and candidate rules. */
export function workflowAccountPage(http: HttpClient): NonNullable<RecordReferencePickerSource['loadPage']> {
  return ({ keyword, pageNum, pageSize }) =>
    http.request({
      method: 'POST',
      path: '/iam.user/selector/query',
      body: { keyword: keyword || undefined, enabledOnly: true, page: { pageNum, pageSize } },
    });
}
