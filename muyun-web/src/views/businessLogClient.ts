import type { HttpClient } from '@muyun/web-core';
import type { WebQueryRequest } from '@muyun/web-contracts';

export type BusinessLogSurface = 'activity' | 'request-error' | 'login';

export interface BusinessLogOperatorIdentity {
  employeeName?: string;
  username?: string;
  organizationId?: string;
  organizationName?: string;
  departmentId?: string;
  departmentName?: string;
}

export interface BusinessLogEventView {
  eventId: string;
  eventType: string;
  occurredAt?: string;
  capturedAt?: string;
  traceId?: string;
  tenantId?: string;
  operatorId?: string;
  operatorOrganizationId?: string;
  operatorIdentity?: BusinessLogOperatorIdentity;
  moduleAlias?: string;
  actionCode?: string;
  outcome?: string;
  summary?: string;
  details?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface BusinessLogStatisticItem {
  key: string;
  title: string;
  count: number;
  [key: string]: unknown;
}

export interface BusinessLogStatistics {
  items: BusinessLogStatisticItem[];
  complete?: boolean;
}

export interface BusinessLogClient {
  detail(eventId: string): Promise<BusinessLogEventView>;
  actionStatistics?(request: WebQueryRequest): Promise<BusinessLogStatistics>;
  pageAccessStatistics?(request: WebQueryRequest): Promise<BusinessLogStatistics>;
  diagnostic?(eventId: string): Promise<Record<string, unknown>>;
}

const endpointBySurface: Record<BusinessLogSurface, string> = {
  activity: '/platform.business_activity_log',
  'request-error': '/platform.request_error_log',
  login: '/iam.login_audit_log',
};

export function createBusinessLogClient(http: HttpClient, surface: BusinessLogSurface): BusinessLogClient {
  const endpoint = endpointBySurface[surface];
  return {
    async detail(eventId) {
      const response = await http.request<unknown>({ path: `${endpoint}/${encodeURIComponent(eventId)}` });
      return normalizeEvent(response);
    },
    ...(surface === 'activity'
      ? {
          actionStatistics: async (input: WebQueryRequest) =>
            normalizeStatistics(
              await http.request<unknown>({
                method: 'POST',
                path: `${endpoint}/statistics/actions`,
                body: input,
              }),
            ),
          pageAccessStatistics: async (input: WebQueryRequest) =>
            normalizeStatistics(
              await http.request<unknown>({
                method: 'POST',
                path: `${endpoint}/statistics/page-access`,
                body: input,
              }),
            ),
        }
      : {}),
    ...(surface === 'request-error'
      ? {
          diagnostic: async (eventId: string) => {
            const response = await http.request<unknown>({
              path: `${endpoint}/${encodeURIComponent(eventId)}/diagnostic`,
            });
            const envelope = recordOf(response);
            return recordOf(envelope.diagnostic ?? response);
          },
        }
      : {}),
  };
}

function normalizeEvent(response: unknown): BusinessLogEventView {
  const envelope = recordOf(response);
  const record = recordOf(envelope.event ?? envelope.record ?? response);
  return {
    ...record,
    eventId: stringOf(record.eventId ?? record.id) ?? '',
    eventType: stringOf(record.eventType ?? record.type) ?? '',
    occurredAt: stringOf(record.occurredAt),
    capturedAt: stringOf(record.capturedAt),
    traceId: stringOf(record.traceId),
    tenantId: stringOf(record.tenantId),
    operatorId: stringOf(record.operatorId),
    operatorOrganizationId: stringOf(record.operatorOrganizationId),
    operatorIdentity: operatorIdentityOf(record),
    moduleAlias: stringOf(record.moduleAlias),
    actionCode: stringOf(record.actionCode),
    outcome: stringOf(record.outcome ?? record.result ?? recordOf(record.details).outcome),
    summary: logSummary(record, recordOf(record.details)),
    details: recordOfOrUndefined(record.details),
  };
}

function normalizeStatistics(response: unknown): BusinessLogStatistics {
  const record = recordOf(response);
  if (Array.isArray(record.pages)) {
    return {
      items: record.pages.map((item) => {
        const page = recordOf(item);
        return {
          key: stringOf(page.pageKey) ?? 'unknown-page',
          title: stringOf(page.pageKey) ?? '未知页面',
          count: numberOf(page.accessCount),
        };
      }),
      complete: booleanOf(record.complete),
    };
  }
  const metrics: Array<[string, string]> = [
    ['executionCount', '执行次数'],
    ['successCount', '成功次数'],
    ['failureCount', '失败次数'],
    ['rejectedCount', '拒绝次数'],
    ['knownDurationCount', '含耗时记录数'],
    ['totalDurationMillis', '累计耗时（毫秒）'],
    ['knownAffectedRecordCount', '含影响数记录数'],
    ['totalAffectedRecordCount', '累计影响记录数'],
  ];
  return {
    items: metrics.map(([key, title]) => ({ key, title, count: numberOf(record[key]) })),
    complete: booleanOf(record.complete),
  };
}

function logSummary(record: Record<string, unknown>, details: Record<string, unknown>) {
  return (
    stringOf(record.summary ?? record.message ?? record.description) ??
    logTextOf(details.message) ??
    logTextOf(details.responseSummary) ??
    stringOf(details.reasonCode) ??
    stringOf(details.failureStage) ??
    stringOf(details.pageKey) ??
    stringOf(details.errorCode) ??
    stringOf(details.authenticationMethod)
  );
}

function logTextOf(value: unknown) {
  return stringOf(value) ?? stringOf(recordOf(value).value);
}

function operatorIdentityOf(record: Record<string, unknown>): BusinessLogOperatorIdentity | undefined {
  const identity = recordOf(record.operatorIdentity);
  const employeeName = stringOf(identity.employeeName);
  const username = stringOf(identity.username);
  const organizationId = stringOf(identity.organizationId);
  const organizationName = stringOf(identity.organizationName);
  const departmentId = stringOf(identity.departmentId);
  const departmentName = stringOf(identity.departmentName);
  return employeeName || username || organizationId || organizationName || departmentId || departmentName
    ? { employeeName, username, organizationId, organizationName, departmentId, departmentName }
    : undefined;
}

function recordOf(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function recordOfOrUndefined(value: unknown): Record<string, unknown> | undefined {
  const record = recordOf(value);
  return Object.keys(record).length > 0 ? record : undefined;
}

function stringOf(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function numberOf(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function booleanOf(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}
