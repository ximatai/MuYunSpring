import type { HttpClient } from '@muyun/web-core';
import type { RecordPickerRecord } from './recordPickerConstraints';

/** Accounts use IAM's dedicated selector rather than its tenant-navigated management list. */
export function workflowAccountOptions(http: HttpClient) {
  return async (keyword: string): Promise<RecordPickerRecord[]> => {
    const result = await http.request<{ records: RecordPickerRecord[] }>({
      method: 'POST',
      path: '/iam.user/selector/query',
      body: { keyword: keyword || undefined, enabledOnly: true, page: { pageNum: 1, pageSize: 50 } },
    });
    return result.records;
  };
}
