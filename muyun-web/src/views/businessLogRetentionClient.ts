import type { HttpClient } from '@muyun/web-core';

export type BusinessLogEventType = 'LOGIN' | 'ACTION' | 'REQUEST_ERROR' | 'PAGE_ACCESS';

export interface BusinessLogRetentionPolicy {
  eventType: BusinessLogEventType;
  automaticCleanupEnabled: boolean;
  retentionDays: number;
  updatedAt?: string;
  updatedBy?: string;
}

export interface BusinessLogRetentionRunResult {
  eventType: BusinessLogEventType;
  retentionDays: number;
  result: {
    occurredBefore: string;
    deletedCount: number;
    executedBatches: number;
    status: 'COMPLETE' | 'BATCH_LIMIT_REACHED' | 'ALREADY_RUNNING';
  };
}

export function createBusinessLogRetentionClient(http: HttpClient) {
  const endpoint = '/platform.business_log_retention';
  return {
    async policies(): Promise<BusinessLogRetentionPolicy[]> {
      const response = await http.request<unknown>({ path: `${endpoint}/policies` });
      return Array.isArray(response) ? (response as BusinessLogRetentionPolicy[]) : [];
    },
    async update(policy: BusinessLogRetentionPolicy): Promise<BusinessLogRetentionPolicy> {
      return (await http.request<unknown>({
        method: 'POST',
        path: `${endpoint}/policies/${policy.eventType}`,
        body: {
          automaticCleanupEnabled: policy.automaticCleanupEnabled,
          retentionDays: policy.retentionDays,
        },
      })) as BusinessLogRetentionPolicy;
    },
    async purge(eventType: BusinessLogEventType): Promise<BusinessLogRetentionRunResult> {
      return (await http.request<unknown>({
        method: 'POST',
        path: `${endpoint}/policies/${eventType}/purge`,
      })) as BusinessLogRetentionRunResult;
    },
  };
}
