import type { AssistantCapability, AssistantInvocationToken } from '@muyun/web-core';
import { flattenTreeRecords, type QueryListRecord } from '@muyun/platform-components';
import type { ModulePageSessionView } from './useModulePageSession';
import type { TenantScopeController } from './useTenantScopeController';

const MAX_ASSISTANT_SCOPE_OPTIONS = 20;

export type ModulePageAssistantTenantScope = Pick<
  TenantScopeController,
  'blocked' | 'changeTenantScope' | 'selected' | 'tenantScopeContext' | 'tenantScopeExplorerVisible'
> & {
  settleTenantScopeChange(
    record: QueryListRecord,
    signal: AbortSignal,
  ): Promise<void | AssistantInvocationToken>;
};

export interface AssistantScopeCandidate {
  scopeKey: string;
  record: QueryListRecord;
  revision: string;
}

function scopeRevision(
  view: ModulePageSessionView,
  tenantScope: ModulePageAssistantTenantScope | undefined,
  scopeKey: string,
) {
  return JSON.stringify([
    tenantScope?.selected.value?.id ?? null,
    scopeKey === 'tenant' ? null : view.assistantNavigatorScopeRevision(scopeKey),
  ]);
}

export function modulePageScopeCapabilities(
  view: ModulePageSessionView,
  tenantScope: ModulePageAssistantTenantScope | undefined,
  candidates: Map<string, AssistantScopeCandidate>,
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
  const scopeKeys = [
    ...(capabilities.some(({ descriptor }) => descriptor.code === 'scope.select-tenant') ? ['tenant'] : []),
    ...navigatorKeys,
  ];
  if (scopeKeys.length) {
    capabilities.push(scopeSearchCapability(view, tenantScope, scopeKeys, candidates));
    capabilities.push({
      descriptor: {
        code: 'scope.select-candidate',
        description: '应用 scope.search 返回的 selectionKey。使用候选凭据，不把展示标签当作名称重新搜索。',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          required: ['selectionKey'],
          properties: { selectionKey: { type: 'string' } },
        },
      },
      parseInput(input) {
        if (!isRecord(input) || typeof input.selectionKey !== 'string')
          throw new Error('Scope candidate key is required');
        return input.selectionKey;
      },
      async execute(input, context) {
        const candidate = candidates.get(String(input));
        if (
          !candidate ||
          !scopeKeys.includes(candidate.scopeKey) ||
          candidate.revision !== scopeRevision(view, tenantScope, candidate.scopeKey)
        ) {
          throw new Error('Scope candidate expired; search again');
        }
        const capability =
          candidate.scopeKey === 'tenant'
            ? tenantScopeSelectionCapability(tenantScope!, candidate.record)
            : navigatorScopeSelectionCapability(view, navigatorKeys, candidate.record);
        return capability.execute(
          capability.parseInput({ scopeKey: candidate.scopeKey, title: scopeRecordTitle(candidate.record) }),
          context,
        );
      },
    });
  }
  return capabilities;
}

function scopeSearchCapability(
  view: ModulePageSessionView,
  tenantScope: ModulePageAssistantTenantScope | undefined,
  scopeKeys: string[],
  candidates: Map<string, AssistantScopeCandidate>,
): AssistantCapability<{ scopeKey: string; keyword: string; page: number }> {
  return {
    descriptor: {
      code: 'scope.search',
      description:
        '查询当前授权范围内的租户或导航候选。缺少范围名称时先查询，再让用户选择；不自动选择。使用返回的 selectionKey 调用 scope.select-candidate。',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['scopeKey'],
        properties: {
          scopeKey: { type: 'string', enum: scopeKeys },
          keyword: { type: 'string', maxLength: 500 },
          page: { type: 'integer', minimum: 1, maximum: 1000 },
        },
      },
    },
    parseInput(input) {
      if (
        !isRecord(input) ||
        typeof input.scopeKey !== 'string' ||
        !scopeKeys.includes(input.scopeKey) ||
        (input.keyword !== undefined && (typeof input.keyword !== 'string' || input.keyword.length > 500)) ||
        (input.page !== undefined &&
          (!Number.isInteger(input.page) || Number(input.page) < 1 || Number(input.page) > 1000))
      ) {
        throw new Error('Invalid scope search');
      }
      return {
        scopeKey: input.scopeKey,
        keyword: String(input.keyword ?? '').trim(),
        page: Number(input.page ?? 1),
      };
    },
    async execute({ scopeKey, keyword, page }, context) {
      const revision = scopeRevision(view, tenantScope, scopeKey);
      let records: QueryListRecord[];
      let total: number;
      let secondary: string | undefined;
      if (scopeKey === 'tenant') {
        const source = tenantScope?.tenantScopeContext.value;
        if (!source || tenantScope?.blocked.value) throw new Error('Tenant scope is unavailable');
        const response = await source.crud.query({
          page: { pageNum: page, pageSize: MAX_ASSISTANT_SCOPE_OPTIONS },
          ...(keyword ? { quickSearch: keyword } : {}),
        });
        records = response.records;
        total = response.total;
        secondary = 'alias';
      } else {
        const level = view.assistantNavigatorScopes().find((item) => item.descriptor.key === scopeKey);
        if (!level) throw new Error('Navigator scope is unavailable');
        secondary = level.descriptor.secondaryField;
        const request = {
          externalQueryValues: view.navigatorExplorerQueryValues(scopeKey),
          navigatorHostModuleAlias: view.context.moduleAlias,
          navigatorTargetLevelKey: scopeKey,
        };
        if (level.tree) {
          const response = await level.context.abilities.tree().tree(request);
          const matches = flattenTreeRecords(response.records).filter(
            (record) =>
              !keyword ||
              normalizeScopeTitle(scopeRecordDisplayTitle(record, secondary) ?? '').includes(
                normalizeScopeTitle(keyword),
              ),
          );
          total = matches.length;
          records = matches.slice(
            (page - 1) * MAX_ASSISTANT_SCOPE_OPTIONS,
            page * MAX_ASSISTANT_SCOPE_OPTIONS,
          );
        } else {
          const response = await level.context.crud.query({
            ...request,
            page: { pageNum: page, pageSize: MAX_ASSISTANT_SCOPE_OPTIONS },
            ...(keyword ? { quickSearch: keyword } : {}),
          });
          records = response.records;
          total = response.total;
        }
      }
      if (!context.isCurrent() || revision !== scopeRevision(view, tenantScope, scopeKey))
        throw new Error('Scope search is no longer current');
      const options = records
        .filter((record) => record.id != null && scopeRecordTitle(record))
        .map((record) => ({ selectionKey: crypto.randomUUID(), record }));
      context.commitInternalState(() => {
        candidates.clear();
        for (const option of options)
          candidates.set(option.selectionKey, { scopeKey, record: option.record, revision });
      });
      return {
        scopeKey,
        page,
        total,
        hasMore: page * MAX_ASSISTANT_SCOPE_OPTIONS < total,
        candidates: options.map(({ selectionKey, record }) => ({
          selectionKey,
          title: scopeRecordTitle(record),
          label: scopeRecordDisplayTitle(record, secondary),
        })),
      };
    },
  };
}

function tenantScopeSelectionCapability(
  tenantScope: ModulePageAssistantTenantScope,
  candidate?: QueryListRecord,
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
      const response = candidate
        ? { records: [candidate], total: 1 }
        : await scopeContext.crud.query({
            page: { pageNum: 1, pageSize: MAX_ASSISTANT_SCOPE_OPTIONS },
            quickSearch: title,
          });
      const selected =
        candidate ??
        requireUniqueExactScopeRecord(response.records, response.total, title, 'tenant', 'alias');
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
  candidate?: QueryListRecord,
): AssistantCapability<{ scopeKey: string; title: string }> {
  const levels = view.assistantNavigatorScopes?.() ?? [];
  const titles = Object.fromEntries(levels.map((level) => [level.descriptor.key, level.descriptor.title]));
  return {
    descriptor: {
      code: 'scope.select-navigator',
      description:
        '按已知名称选择当前页面的导航范围，仅在授权查询返回唯一精确匹配时应用。名称未知时先用 scope.search 查询候选。',
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
      if (candidate) {
        records = [candidate];
        total = 1;
      } else if (level.tree) {
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
      const selected =
        candidate ??
        requireUniqueExactScopeRecord(
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
          await view.settleAssistantPageState(context.cancellationSignal ?? context.signal);
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
