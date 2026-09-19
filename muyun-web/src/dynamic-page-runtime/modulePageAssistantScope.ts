import type { AssistantCapability } from '@muyun/web-core';
import { flattenTreeRecords, type QueryListRecord } from '@muyun/platform-components';
import type { ModulePageSessionView } from './useModulePageSession';
import type { TenantScopeController } from './useTenantScopeController';

const MAX_ASSISTANT_SCOPE_OPTIONS = 20;

export type ModulePageAssistantTenantScope = Pick<
  TenantScopeController,
  'blocked' | 'changeTenantScope' | 'selected' | 'tenantScopeContext' | 'tenantScopeExplorerVisible'
> & {
  settleTenantScopeChange(record: QueryListRecord, signal: AbortSignal): Promise<void>;
};

export function modulePageScopeCapabilities(
  view: ModulePageSessionView,
  tenantScope: ModulePageAssistantTenantScope | undefined,
): AssistantCapability[] {
  const capabilities: AssistantCapability[] = [];
  if (
    tenantScope?.tenantScopeExplorerVisible.value === true &&
    tenantScope.tenantScopeContext.value &&
    !tenantScope.blocked.value
  ) {
    capabilities.push(tenantScopeSelectionCapability(tenantScope));
  }
  const navigatorKeys = (view.assistantNavigatorScopes?.() ?? []).map((level) => level.descriptor.key);
  if (navigatorKeys.length > 0) capabilities.push(navigatorScopeSelectionCapability(view, navigatorKeys));
  return capabilities;
}

function tenantScopeSelectionCapability(
  tenantScope: ModulePageAssistantTenantScope,
): AssistantCapability<{ title: string }> {
  return {
    descriptor: {
      code: 'scope.select-tenant',
      description:
        '按租户名称选择当前标准页面的业务租户范围。仅在授权查询返回唯一精确匹配时切换范围；页面会随之重新加载。',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['title'],
        properties: { title: { type: 'string', minLength: 1, maxLength: 500 } },
      },
    },
    parseInput: parseScopeTitle,
    async execute({ title }, context) {
      const scopeContext = tenantScope.tenantScopeContext.value;
      if (!scopeContext || tenantScope.blocked.value) throw new Error('Tenant scope is not available');
      const initialTenantId = String(tenantScope.selected.value?.id ?? '');
      const response = await scopeContext.crud.query({
        page: { pageNum: 1, pageSize: MAX_ASSISTANT_SCOPE_OPTIONS },
        quickSearch: title,
      });
      const selected = requireUniqueExactScopeRecord(
        response.records,
        response.total,
        title,
        'tenant',
        'alias',
      );
      if (!context.isCurrent() || String(tenantScope.selected.value?.id ?? '') !== initialTenantId) {
        throw new Error('Tenant scope selection is no longer current');
      }
      if (String(tenantScope.selected.value?.id ?? '') === String(selected.id)) {
        return {
          scope: 'tenant',
          selectedTitle: scopeRecordResultTitle(selected, 'alias'),
          changed: false,
        };
      }
      context.applyEffect(
        () => {
          if (tenantScope.blocked.value || String(tenantScope.selected.value?.id ?? '') !== initialTenantId) {
            throw new Error('Tenant scope selection is no longer available');
          }
          tenantScope.changeTenantScope(selected);
        },
        () => tenantScope.settleTenantScopeChange(selected, context.cancellationSignal ?? context.signal),
      );
      return {
        scope: 'tenant',
        selectedTitle: scopeRecordResultTitle(selected, 'alias'),
        changed: true,
      };
    },
  };
}

function navigatorScopeSelectionCapability(
  view: ModulePageSessionView,
  navigatorKeys: string[],
): AssistantCapability<{ scopeKey: string; title: string }> {
  const levels = view.assistantNavigatorScopes?.() ?? [];
  const titles = Object.fromEntries(levels.map((level) => [level.descriptor.key, level.descriptor.title]));
  return {
    descriptor: {
      code: 'scope.select-navigator',
      description:
        '按名称选择当前页面的导航查询范围（例如机构、部门或分类）。先选择上游范围；仅在授权查询返回唯一精确匹配时应用。',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['scopeKey', 'title'],
        properties: {
          scopeKey: { type: 'string', enum: navigatorKeys },
          title: { type: 'string', minLength: 1, maxLength: 500 },
        },
      },
    },
    parseInput(input) {
      if (
        !isRecord(input) ||
        typeof input.scopeKey !== 'string' ||
        !navigatorKeys.includes(input.scopeKey) ||
        typeof input.title !== 'string' ||
        !input.title.trim() ||
        input.title.length > 500
      ) {
        throw new Error('scope.select-navigator requires an available scopeKey and title');
      }
      return { scopeKey: input.scopeKey, title: input.title.trim() };
    },
    async execute({ scopeKey, title }, context) {
      const level = view
        .assistantNavigatorScopes?.()
        .find((candidate) => candidate.descriptor.key === scopeKey);
      if (!level) {
        throw new Error(`Navigator scope is not available: ${scopeKey}`);
      }
      const scopeRevision = view.assistantNavigatorScopeRevision(scopeKey);
      const request = {
        externalQueryValues: view.navigatorExplorerQueryValues(scopeKey),
        navigatorHostModuleAlias: view.context.moduleAlias,
        navigatorTargetLevelKey: scopeKey,
      };
      let records: QueryListRecord[];
      let total: number;
      if (level.tree) {
        const response = await level.context.abilities.tree().tree(request);
        records = flattenTreeRecords(response.records);
        total = records.length;
      } else {
        const response = await level.context.crud.query({
          ...request,
          page: { pageNum: 1, pageSize: MAX_ASSISTANT_SCOPE_OPTIONS },
          quickSearch: title,
        });
        records = response.records;
        total = response.total;
      }
      const selected = requireUniqueExactScopeRecord(
        records,
        total,
        title,
        titles[scopeKey] ?? scopeKey,
        level.descriptor.secondaryField,
      );
      if (!context.isCurrent() || view.assistantNavigatorScopeRevision(scopeKey) !== scopeRevision) {
        throw new Error('Navigator scope selection is no longer current');
      }
      if (String(view.selectedNavigatorRecords[scopeKey]?.id ?? '') === String(selected.id)) {
        return {
          scopeKey,
          selectedTitle: scopeRecordResultTitle(selected, level.descriptor.secondaryField),
          changed: false,
        };
      }
      let appliedRevision: string | undefined;
      context.applyEffect(
        () => {
          if (!view.applyAssistantNavigatorSelection(scopeKey, selected, scopeRevision)) {
            throw new Error('Navigator scope selection is no longer available');
          }
          appliedRevision = view.assistantNavigatorScopeRevision(scopeKey);
        },
        async () => {
          await view.settleAssistantNavigatorSelection(context.signal);
          if (
            String(view.selectedNavigatorRecords[scopeKey]?.id ?? '') !== String(selected.id) ||
            view.assistantNavigatorScopeRevision(scopeKey) !== appliedRevision
          ) {
            throw new Error('Navigator scope selection was replaced before its query settled');
          }
        },
      );
      return {
        scopeKey,
        selectedTitle: scopeRecordResultTitle(selected, level.descriptor.secondaryField),
        changed: true,
      };
    },
  };
}

function parseScopeTitle(input: unknown) {
  if (
    !isRecord(input) ||
    typeof input.title !== 'string' ||
    !input.title.trim() ||
    input.title.length > 500
  ) {
    throw new Error('scope selection requires a title');
  }
  return { title: input.title.trim() };
}

function requireUniqueExactScopeRecord(
  records: QueryListRecord[],
  total: number,
  title: string,
  scope: string,
  secondaryField?: string,
): QueryListRecord {
  const normalized = normalizeScopeTitle(title);
  const matches = records.filter(
    (record) =>
      record.id != null &&
      [...scopeRecordLabels(record), scopeRecordDisplayTitle(record, secondaryField)]
        .filter((value) => value != null)
        .some((value) => normalizeScopeTitle(String(value)) === normalized),
  );
  if (matches.length !== 1) {
    throw new Error(`${scope} title is not a unique exact match; ask the user to clarify`);
  }
  if (total > records.length && records.length >= MAX_ASSISTANT_SCOPE_OPTIONS) {
    throw new Error(`${scope} search result is truncated; ask the user to clarify`);
  }
  return matches[0]!;
}

function scopeRecordTitle(record: QueryListRecord) {
  const title = record.title ?? record.name ?? record.code ?? record.alias;
  return title == null ? undefined : String(title);
}

function scopeRecordResultTitle(record: QueryListRecord, secondaryField?: string) {
  return scopeRecordTitle(record) ?? scopeRecordDisplayTitle(record, secondaryField)!;
}

function scopeRecordLabels(record: QueryListRecord) {
  return [record.title, record.name, record.code, record.alias];
}

function scopeRecordDisplayTitle(record: QueryListRecord, secondaryField?: string) {
  const title = scopeRecordTitle(record);
  const secondary = secondaryField ? record[secondaryField] : (record.code ?? record.alias);
  if (title && secondary != null && String(secondary) !== title) return `${title} ${String(secondary)}`;
  return title ?? (secondary == null ? undefined : String(secondary));
}

function normalizeScopeTitle(value: string) {
  return value.trim().toLowerCase();
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
