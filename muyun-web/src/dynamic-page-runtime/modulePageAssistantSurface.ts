import { createRelationDraftAssistantCapabilities } from './relationDraftAssistantCapabilities';
import { assistantRelationProjection } from './assistantRecordProjection';
import type { AssistantSurfaceContext } from '@muyun/web-contracts';
import {
  AssistantCapabilityUsageError,
  type AssistantCapability,
  type AssistantCapabilityExecutionContext,
  type AssistantSurface,
  type AssistantTurnRequester,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
} from '@muyun/web-core';
import type { RecordQueryListQuerySnapshot } from '@muyun/platform-components';
import {
  createRecordFormAssistantCapabilities,
  formFieldState,
  isSensitiveField,
  isRecord,
} from './recordFormAssistantCapabilities';
import { assistantQueryResult, createAssistantQueryCapabilities } from './assistantQueryCapabilities';
import type { ModulePageSessionView } from './useModulePageSession';
import { assistantEditableRecordIds, hasActiveRecordEditor } from './assistantRecordEditorPolicy';
import {
  modulePageScopeCapabilities,
  type ModulePageAssistantTenantScope,
  type AssistantScopeCandidate,
} from './modulePageAssistantScope';

export function modulePageAssistantContextRevision(view: ModulePageSessionView): string {
  const querySnapshot = view.listQueryController?.snapshot();
  return JSON.stringify({
    page: view.assistantContextRevision,
    relations: view.relationDrafts?.revision(),
    query: querySnapshot ? assistantQueryProjectionDigest(querySnapshot) : null,
    tree: view.treeQueryController?.revision() ?? null,
  });
}

function assistantQueryProjectionDigest(snapshot: RecordQueryListQuerySnapshot) {
  const projection = JSON.stringify({
    mode: snapshot.mode,
    quickSearchEnabled: snapshot.quickSearchEnabled,
    rowIds: snapshot.rows.map((row) => row.id ?? null),
  });
  let hash = 0x811c9dc5;
  for (let index = 0; index < projection.length; index += 1) {
    hash ^= projection.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return `${projection.length}:${(hash >>> 0).toString(16)}`;
}

export function modulePageAssistantInteractionRevision(view: ModulePageSessionView): string {
  return JSON.stringify({
    page: view.assistantInteractionRevision,
    relations: view.relationDrafts?.interactionRevision(),
    query: view.listQueryController?.interactionRevision?.() ?? '0',
  });
}

export function createModulePageAssistantSurface(
  view: ModulePageSessionView,
  requestTurn: AssistantTurnRequester,
  contributedCapabilities: () => AssistantCapability[] = () => [],
  tenantScope?: ModulePageAssistantTenantScope,
): AssistantSurface {
  const formCapabilities = createRecordFormAssistantCapabilities({
    get editorMode() {
      return view.editorMode;
    },
    get editingRecord() {
      return view.editingRecord;
    },
    get selectedRecord() {
      return view.selectedRecord;
    },
    get formFields() {
      return view.formFields;
    },
    get referencePickerConfigs() {
      return view.referencePickerConfigs;
    },
    contextRevision: () => modulePageAssistantContextRevision(view),
    relations: () => assistantRelationFacts(view),
    updateDraftFields: (...args) => view.updateDraftFields(...args),
    updateDraftReference: (...args) => view.updateDraftReference(...args),
  });
  const relationCapabilities = view.relationDrafts
    ? createRelationDraftAssistantCapabilities(view.relationDrafts, () =>
        modulePageAssistantContextRevision(view),
      )
    : () => [];
  const scopeCandidates = new Map<string, AssistantScopeCandidate>();
  const capabilities = (): AssistantCapability[] => [
    ...contributedCapabilities(),
    ...modulePageScopeCapabilities(view, tenantScope, scopeCandidates),
    ...(view.listQueryController ? queryCapabilities(view) : []),
    ...(view.treeQueryController ? treeQueryCapabilities(view) : []),
    ...recordEditorCapabilities(view),
    ...(hasEditableDraft(view) ? relationCapabilities() : []),
    ...(hasEditableDraft(view)
      ? [...formCapabilities(), ...(view.assistantSaveAvailable ? [formSaveProposalCapability(view)] : [])]
      : []),
  ];
  return {
    describe: () => surfaceContext(view, tenantScope),
    capabilities,
    requestTurn,
  };
}

function recordEditorCapabilities(view: ModulePageSessionView): AssistantCapability[] {
  if (view.editorMode !== 'view' || view.detailLoading || view.detailLoadFailed) return [];
  const querySnapshot = view.listQueryController?.snapshot();
  if (querySnapshot?.mode === 'recycleBin') return [];
  const capabilities: AssistantCapability[] = [];
  if (view.recordCreationState().ready) {
    capabilities.push({
      effect: 'page',
      descriptor: {
        code: 'record.start-create',
        description: '打开当前模块的标准新增表单并建立未保存草稿；需要新建单据或记录时使用。它不会保存。',
        inputSchema: emptyAssistantCapabilityInputSchema(),
      },
      parseInput: parseEmptyAssistantCapabilityInput,
      async execute(_input, context) {
        const commit = await view.prepareAssistantCreate();
        return context.applyEffect(commit, () =>
          view.settleAssistantPageState(context.cancellationSignal ?? context.signal),
        );
      },
    });
  }
  const editableRecordIds = assistantEditableRecordIds(view.selectedRecord?.id, querySnapshot);
  if (view.context.can('update') === true && editableRecordIds.length > 0) {
    capabilities.push({
      effect: 'page',
      descriptor: {
        code: 'record.start-edit',
        description:
          '打开当前页面已选中或当前列表结果中某条记录的标准编辑表单；只能使用当前页面提供的 recordId。它不会保存。',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          required: ['recordId'],
          properties: { recordId: { type: 'string', enum: editableRecordIds } },
        },
      },
      parseInput(input) {
        if (
          !isRecord(input) ||
          typeof input.recordId !== 'string' ||
          !editableRecordIds.includes(input.recordId)
        ) {
          throw new AssistantCapabilityUsageError(
            'record.start-edit requires a recordId from the current page',
          );
        }
        return { recordId: input.recordId };
      },
      async execute(input, context) {
        const { recordId } = input as { recordId: string };
        const commit = await view.prepareAssistantEdit(recordId);
        return context.applyEffect(commit, () =>
          view.settleAssistantPageState(context.cancellationSignal ?? context.signal),
        );
      },
    });
  }
  return capabilities;
}

function hasEditableDraft(view: ModulePageSessionView) {
  return hasActiveRecordEditor(view.editorMode, view.editingRecord);
}

function queryCapabilities(view: ModulePageSessionView): AssistantCapability[] {
  const source = view.listQueryController!;
  const controller = {
    ...source,
    snapshot: () => assistantQuerySnapshot(view, source.snapshot()),
    settle: async (...args: Parameters<typeof source.settle>) =>
      assistantQuerySnapshot(view, await source.settle(...args)),
  };
  const snapshot = controller.snapshot();
  return [
    ...createAssistantQueryCapabilities(controller),
    ...(snapshot.quickSearchEnabled
      ? [
          {
            effect: 'page',
            descriptor: {
              code: 'query.apply-quick-search',
              description:
                '搜索或筛选当前列表：把用户给出的字面关键词交给平台标准快速搜索，并返回筛选后的当前页。用户要求查找、搜索或筛选记录时直接使用。',
              inputSchema: {
                type: 'object' as const,
                additionalProperties: false,
                required: ['keyword'],
                properties: { keyword: { type: 'string', maxLength: 500 } },
              },
            },
            parseInput(input: unknown) {
              if (!isRecord(input) || typeof input.keyword !== 'string') {
                throw new AssistantCapabilityUsageError('query.apply-quick-search requires a keyword');
              }
              return { keyword: input.keyword };
            },
            async execute(input: unknown, context: AssistantCapabilityExecutionContext) {
              const keyword = (input as { keyword: string }).keyword;
              let pending!: Promise<ReturnType<typeof controller.snapshot>>;
              context.applyEffect(
                () => {
                  pending = (async () => {
                    await controller.applyQuickSearch(keyword);
                    return controller.settle(context.cancellationSignal ?? context.signal);
                  })();
                },
                () => pending.then(() => undefined),
              );
              return pending.then(assistantQueryResult);
            },
          } satisfies AssistantCapability,
        ]
      : []),
    {
      effect: 'read',
      descriptor: {
        code: 'query.describe',
        description: '读取当前标准列表的查询状态和可见结果页；它不会筛选记录，仅在需要了解当前结果时使用。',
        inputSchema: emptyAssistantCapabilityInputSchema(),
      },
      parseInput: parseEmptyAssistantCapabilityInput,
      async execute() {
        return assistantQueryResult(controller.snapshot());
      },
    },
  ];
}

function treeQueryCapabilities(view: ModulePageSessionView): AssistantCapability[] {
  const source = view.treeQueryController!;
  const primary = view.runtimePage?.explorer?.titleField ?? 'title';
  const secondary = view.runtimePage?.explorer?.secondaryField;
  if (!assistantReadableName(view, primary)) return [];
  const safeNode = (node: ReturnType<typeof source.select>) =>
    secondary && !assistantReadableName(view, secondary)
      ? { selectionKey: node.selectionKey, title: node.title }
      : node;
  const controller = {
    ...source,
    select: (key: string) => safeNode(source.select(key)),
    snapshot: () => {
      const snapshot = source.snapshot();
      return { ...snapshot, nodes: snapshot.nodes.map(safeNode) };
    },
  };
  const selectionKeys = controller.snapshot().nodes.map(({ selectionKey }) => selectionKey);
  return [
    {
      effect: 'read',
      descriptor: {
        code: 'tree.describe',
        description: '读取当前树形页面已加载的授权节点；需要了解或查找已有树节点时使用。',
        inputSchema: emptyAssistantCapabilityInputSchema(),
      },
      parseInput: parseEmptyAssistantCapabilityInput,
      async execute() {
        return controller.snapshot();
      },
    },
    {
      effect: 'page',
      descriptor: {
        code: 'tree.select-record',
        description:
          '使用 tree.describe 返回的不透明 selectionKey 选中当前树中的记录；不得猜测 selectionKey。',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          required: ['selectionKey'],
          properties: { selectionKey: { type: 'string', enum: selectionKeys } },
        },
      },
      parseInput(input) {
        if (
          !isRecord(input) ||
          typeof input.selectionKey !== 'string' ||
          !selectionKeys.includes(input.selectionKey)
        ) {
          throw new AssistantCapabilityUsageError(
            'tree.select-record requires a selectionKey from tree.describe',
          );
        }
        return { selectionKey: input.selectionKey };
      },
      async execute({ selectionKey }, context) {
        let selected!: ReturnType<typeof controller.select>;
        context.applyEffect(
          () => {
            selected = controller.select(selectionKey);
          },
          () => view.settleAssistantPageState(context.cancellationSignal ?? context.signal),
        );
        return selected;
      },
    },
  ];
}

function surfaceContext(
  view: ModulePageSessionView,
  tenantScope?: ModulePageAssistantTenantScope,
): AssistantSurfaceContext {
  const navigatorScopes = view.assistantNavigatorScopes().map((level) => {
    const selected = view.selectedNavigatorRecords[level.descriptor.key];
    const selectedTitle = selected ? recordTitle(selected) : undefined;
    return {
      key: level.descriptor.key,
      title: level.descriptor.title,
      selected: selectedTitle ?? null,
    };
  });
  return {
    surface: 'module-page',
    title: view.modulePageTitle,
    facts: {
      moduleAlias: view.context.moduleAlias,
      editorMode: view.editorMode,
      selectedRecordId: recordIdentity(view.selectedRecord),
      editing: hasEditableDraft(view),
      dirty: view.detailDirty,
      creation: view.recordCreationState(),
      relations: assistantRelationFacts(view),
      ...(tenantScope?.tenantScopeExplorerVisible.value
        ? { tenant: tenantScope.selected.value ? recordTitle(tenantScope.selected.value) : null }
        : {}),
      ...(navigatorScopes.length > 0 ? { navigatorScopes } : {}),
    },
  };
}

function assistantRelationFacts(view: ModulePageSessionView) {
  return assistantRelationProjection(
    view.runtimeUiDescriptor,
    view.executableDetailRelations ?? [],
    view.assistantDisplayRecord ?? view.editingRecord ?? view.selectedRecord ?? {},
    {
      baseline: view.selectedRecord,
      relationOptions: view.assistantRelationOptions,
      editableRelations: new Set(view.relationDrafts?.list().map((item) => item.code) ?? []),
    },
  );
}

function recordTitle(record: Record<string, unknown>) {
  const title = record.title ?? record.name ?? record.code ?? record.alias;
  return title == null ? undefined : String(title).slice(0, 500);
}

function recordIdentity(record: { id?: unknown; version?: unknown } | undefined) {
  if (record?.id === undefined || record.id === null) return undefined;
  return { id: String(record.id), version: record.version };
}

function assistantReadableName(view: ModulePageSessionView, name: string): boolean {
  if (name.includes('.')) return false;
  const field = formFieldState(view, name);
  if (field && (isSensitiveField(field) || field.assistantPolicy === 'DESCRIBE')) return false;
  // Display-only fields need the same protection ceiling as editor fields.
  const descriptor = view.context.runtime?.snapshot()?.uiDescriptor;
  const page = view.runtimePage ?? descriptor?.page;
  const views = [
    page?.detail?.display,
    page?.detail?.editor,
    page?.list?.fields,
    descriptor?.defaultEditor,
    ...(descriptor?.editorSurfaces?.map((surface) => surface.editor) ?? []),
  ];
  return !views.some((source) =>
    source?.fields.some(
      (candidate) =>
        !candidate.fieldRef.relationCode &&
        candidate.fieldRef.fieldName === name &&
        (candidate.assistantPolicy === 'HIDDEN' ||
          candidate.assistantPolicy === 'DESCRIBE' ||
          candidate.fieldControl?.alias === 'password'),
    ),
  );
}

function assistantQuerySnapshot(
  view: ModulePageSessionView,
  snapshot: RecordQueryListQuerySnapshot,
): RecordQueryListQuerySnapshot {
  const sourceName = (name: string) =>
    name === 'title'
      ? (view.runtimePage?.explorer?.titleField ?? name)
      : name === 'secondary'
        ? (view.runtimePage?.explorer?.secondaryField ?? name)
        : name;
  return {
    ...snapshot,
    quickSearchFields: snapshot.quickSearchFields.filter((field) => assistantReadableName(view, field.name)),
    rows: snapshot.rows.map((row) => ({
      ...row,
      cells: row.cells.filter((cell) => assistantReadableName(view, sourceName(cell.fieldName))),
    })),
    ...(snapshot.standardQuery
      ? {
          standardQuery: {
            fields: snapshot.standardQuery.fields.filter((field) => assistantReadableName(view, field.name)),
            conditions: snapshot.standardQuery.conditions.filter((condition) =>
              assistantReadableName(view, condition.fieldName),
            ),
            sorts: snapshot.standardQuery.sorts.filter((sort) => assistantReadableName(view, sort.field)),
          },
        }
      : {}),
  };
}

function formSaveProposalCapability(view: ModulePageSessionView): AssistantCapability {
  let proposal: Awaited<ReturnType<ModulePageSessionView['prepareAssistantSave']>> | undefined;
  return {
    effect: 'read',
    descriptor: {
      code: 'form.prepare-save',
      description: '准备当前标准表单的保存确认卡片。用户在对话中审阅并点击确认后才保存；本工具不会保存。',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute(_input, context) {
      const prepared = await view.prepareAssistantSave();
      context.commitInternalState(() => {
        proposal = prepared;
      });
      return { awaitingHumanConfirmation: true };
    },
    propose() {
      if (!proposal) throw new Error('Save proposal was not prepared');
      return proposal;
    },
  };
}
