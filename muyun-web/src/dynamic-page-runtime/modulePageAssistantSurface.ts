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
  pageAssistantCatalog,
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
import type { RecordFormDraftAccess } from './recordFormDraftAccess';
import { assistantVisibleRecordIds, hasActiveRecordEditor } from './assistantRecordEditorPolicy';
import {
  modulePageScopeCapabilities,
  modulePageScopeCandidateFacts,
  type ModulePageAssistantTenantScope,
  type AssistantScopeCandidate,
} from './modulePageAssistantScope';

export function modulePageAssistantContextRevision(view: ModulePageSessionView): string {
  const querySnapshot = view.listQueryController?.snapshot();
  return JSON.stringify({
    page: view.assistantContextRevision,
    relations: view.relationDrafts?.revision(),
    query: querySnapshot ? assistantQueryProjectionDigest(view, querySnapshot) : null,
    tree: view.treeQueryController?.revision() ?? null,
    navigatorEditor: view.assistantNavigatorEditor && [
      view.assistantNavigatorEditor.key,
      view.assistantNavigatorEditor.moduleAlias,
      view.assistantNavigatorEditor.form.editorMode,
      view.assistantNavigatorEditor.form.contextRevision(),
    ],
  });
}

function assistantQueryProjectionDigest(view: ModulePageSessionView, snapshot: RecordQueryListQuerySnapshot) {
  const projection = JSON.stringify({
    preview: assistantQueryContext(view, snapshot),
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
  const activeForm = () => view.assistantNavigatorEditor?.form ?? view;
  const formCapabilities = createRecordFormAssistantCapabilities({
    get editorMode() {
      return activeForm().editorMode;
    },
    get editingRecord() {
      return activeForm().editingRecord;
    },
    get selectedRecord() {
      return activeForm().selectedRecord;
    },
    get formFields() {
      return activeForm().formFields;
    },
    get referencePickerConfigs() {
      return activeForm().referencePickerConfigs;
    },
    contextRevision: () => modulePageAssistantContextRevision(view),
    relations: () => (view.assistantNavigatorEditor ? [] : assistantRelationFacts(view)),
    updateDraftFields: (...args) => activeForm().updateDraftFields(...args),
    updateDraftReference: (...args) => activeForm().updateDraftReference(...args),
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
    ...navigatorCreationCapabilities(view),
    ...(!view.assistantNavigatorEditor && view.listQueryController ? queryCapabilities(view) : []),
    ...(!view.assistantNavigatorEditor && view.treeQueryController ? treeQueryCapabilities(view) : []),
    ...recordEditorCapabilities(view),
    ...cleanEditorExitCapabilities(view),
    ...(!view.assistantNavigatorEditor && hasEditableDraft(view) ? relationCapabilities() : []),
    ...(view.assistantNavigatorEditor?.busy ? [] : formCapabilities()),
    ...(!view.assistantNavigatorEditor && hasEditableDraft(view) && view.assistantSaveAvailable
      ? [formSaveProposalCapability(view)]
      : []),
  ];
  return {
    describe: () => {
      const context = surfaceContext(view, tenantScope);
      return {
        ...context,
        facts: {
          ...context.facts,
          scopeCandidates: modulePageScopeCandidateFacts(view, tenantScope, scopeCandidates),
        },
      };
    },
    capabilities,
    requestTurn,
  };
}

function navigatorCreationCapabilities(view: ModulePageSessionView): AssistantCapability[] {
  const keys = view.assistantNavigatorCreationTargets().map(({ key }) => key);
  if (!keys.length) return [];
  return [
    {
      effect: 'page',
      descriptor: {
        code: 'navigator.start-create',
        description:
          'Open the standard new-record form for a manageable navigator shown in facts.navigatorCreationTargets. Use its scopeKey, not the main module create action. Then describe and fill the active form. This only prepares a visible unsaved draft; the user reviews and saves using the standard editor. It neither creates a main record nor changes the current selection.',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          required: ['scopeKey'],
          properties: { scopeKey: { type: 'string', enum: keys } },
        },
      },
      parseInput(input) {
        if (
          !isRecord(input) ||
          Object.keys(input).some((key) => key !== 'scopeKey') ||
          typeof input.scopeKey !== 'string' ||
          !keys.includes(input.scopeKey)
        )
          throw new AssistantCapabilityUsageError('请选择当前可新建的导航区');
        return { scopeKey: input.scopeKey };
      },
      async execute(input, context) {
        const commit = await view.prepareAssistantNavigatorCreate((input as { scopeKey: string }).scopeKey);
        return context.applyEffect(commit, () =>
          view.settleAssistantPageState(context.cancellationSignal ?? context.signal),
        );
      },
    },
  ];
}

function recordEditorCapabilities(view: ModulePageSessionView): AssistantCapability[] {
  if (
    view.assistantNavigatorEditor ||
    view.editorMode !== 'view' ||
    view.detailLoading ||
    view.detailLoadFailed
  )
    return [];
  const querySnapshot = view.listQueryController?.snapshot();
  if (querySnapshot?.mode === 'recycleBin') return [];
  const capabilities: AssistantCapability[] = [];
  if (view.recordCreationState().ready) {
    capabilities.push({
      effect: 'page',
      descriptor: {
        code: 'record.start-create',
        description:
          '打开当前模块的标准新增表单并建立未保存草稿；树页面创建根记录。要在已有树记录下面创建子项，应先选中父记录，再使用 record.start-create-child。它不会保存。',
        inputSchema: emptyAssistantCapabilityInputSchema(),
      },
      parseInput: parseEmptyAssistantCapabilityInput,
      present: () => ({
        title: `已打开${view.modulePageTitle}新增表单`,
        lines: ['已建立未保存草稿，尚未新增正式记录。'],
      }),
      async execute(_input, context) {
        const commit = await view.prepareAssistantCreate();
        return context.applyEffect(commit, () =>
          view.settleAssistantPageState(context.cancellationSignal ?? context.signal),
        );
      },
    });
    if (view.persistentTreeDetail && !view.managedPageActions && view.selectedRecord?.id != null) {
      capabilities.push({
        effect: 'page',
        descriptor: {
          code: 'record.start-create-child',
          description:
            '在当前已选中的树记录下面打开标准新增子项表单。复用当前导航范围并自动填写上级记录，建立未保存草稿；先通过 tree.select-record 选中真实父节点。它不会保存。',
          inputSchema: emptyAssistantCapabilityInputSchema(),
        },
        parseInput: parseEmptyAssistantCapabilityInput,
        async execute(_input, context) {
          const commit = await view.prepareAssistantCreate({ asChildOfSelectedRecord: true });
          return context.applyEffect(commit, () =>
            view.settleAssistantPageState(context.cancellationSignal ?? context.signal),
          );
        },
      });
    }
  }
  const visibleRecordIds = assistantVisibleRecordIds(view.selectedRecord?.id, querySnapshot);
  for (const mode of ['view', 'edit'] as const) {
    if (mode === 'view' && view.pageEnhancement?.recordView) continue;
    if (view.context.can(mode === 'view' ? 'view' : 'update') !== true || visibleRecordIds.length === 0)
      continue;
    const code = mode === 'view' ? 'record.open-view' : 'record.start-edit';
    capabilities.push({
      effect: 'page',
      descriptor: {
        code,
        description:
          mode === 'view'
            ? '只读打开当前页面已选中或当前列表中的记录详情，读取正式保存的字段和明细。查看、解释或核对数据时使用，不建立编辑草稿；只能使用当前页面提供的 recordId。'
            : '用户要求修改记录时，打开当前页面已选中或当前列表中的记录的标准编辑表单。仅查看数据应使用 record.open-view；只能使用当前页面提供的 recordId。它不会保存。',
        inputSchema: {
          type: 'object',
          additionalProperties: false,
          required: ['recordId'],
          properties: { recordId: { type: 'string', enum: visibleRecordIds } },
        },
      },
      parseInput(input) {
        if (
          !isRecord(input) ||
          Object.keys(input).some((key) => key !== 'recordId') ||
          typeof input.recordId !== 'string' ||
          !visibleRecordIds.includes(input.recordId)
        ) {
          throw new AssistantCapabilityUsageError(`${code} requires a recordId from the current page`);
        }
        return { recordId: input.recordId };
      },
      present: () => ({
        title: `已打开${view.modulePageTitle}${mode === 'view' ? '详情' : '编辑表单'}`,
        lines: [
          mode === 'view'
            ? '当前为只读查看，未修改记录。'
            : '仅打开编辑表单，尚未提交修改；后续填写仍需审阅保存。',
        ],
      }),
      async execute(input, context) {
        const { recordId } = input as { recordId: string };
        const commit = await (mode === 'view'
          ? view.prepareAssistantView(recordId)
          : view.prepareAssistantEdit(recordId));
        return context.applyEffect(commit, () =>
          view.settleAssistantPageState(context.cancellationSignal ?? context.signal),
        );
      },
    });
  }
  return capabilities;
}

function hasEditableDraft(view: Pick<RecordFormDraftAccess, 'editorMode' | 'editingRecord'>) {
  return hasActiveRecordEditor(view.editorMode, view.editingRecord);
}

function cleanEditorExitCapabilities(view: ModulePageSessionView): AssistantCapability[] {
  const ready = () => view.canLeaveUnchangedEditor?.() === true;
  if (!ready()) return [];
  return [
    {
      effect: 'page',
      descriptor: {
        code: 'record.leave-unchanged-editor',
        description:
          '用户要求退出编辑或返回查看时，退出没有未保存更改的标准编辑表单，按页面原有规则回到详情或列表。不保存、不丢弃更改；存在未保存更改时不可用，应交由用户审阅处理。',
        inputSchema: emptyAssistantCapabilityInputSchema(),
      },
      parseInput: parseEmptyAssistantCapabilityInput,
      async execute(_input, context) {
        if (!ready()) throw new AssistantCapabilityUsageError('编辑状态已变化，请先审阅当前草稿');
        let pending!: Promise<void>;
        context.applyEffect(
          () => {
            pending = view.leaveUnchangedEditor();
          },
          () => pending,
        );
        await pending;
        return { editing: false, saved: false };
      },
      present: () => ({ title: '已退出未修改的编辑表单', lines: ['原有保存记录保留，本次没有提交修改。'] }),
    },
  ];
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
        description:
          '读取当前标准列表的完整受限结果页；优先复用 facts.query 中的当前结果，仅在摘要截断或缺少所需信息时读取。它不会筛选记录。',
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
  const capabilities: AssistantCapability[] = [
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
  ];
  if (selectionKeys.length === 0) return capabilities;
  capabilities.push({
    effect: 'page',
    descriptor: {
      code: 'tree.select-record',
      description: '使用 tree.describe 返回的不透明 selectionKey 选中当前树中的记录；不得猜测 selectionKey。',
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
  });
  return capabilities;
}

function surfaceContext(
  view: ModulePageSessionView,
  tenantScope?: ModulePageAssistantTenantScope,
): AssistantSurfaceContext {
  // A draft locks scope changes, but its visible ownership remains a readable fact.
  const navigatorScopes = (view.visibleNavigatorLevels ?? []).map((level) => {
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
      editorMode: view.assistantNavigatorEditor?.form.editorMode ?? view.editorMode,
      selectedRecordId: recordIdentity(
        view.assistantNavigatorEditor
          ? view.assistantNavigatorEditor.form.selectedRecord
          : view.selectedRecord,
      ),
      editing: hasEditableDraft(view.assistantNavigatorEditor?.form ?? view),
      dirty: view.assistantNavigatorEditor?.dirty ?? view.detailDirty,
      ...(view.assistantNavigatorEditor
        ? {
            editorOwner: {
              kind: 'NAVIGATOR',
              key: view.assistantNavigatorEditor.key,
              moduleAlias: view.assistantNavigatorEditor.moduleAlias,
              title: view.assistantNavigatorEditor.title,
              loading: view.assistantNavigatorEditor.loading,
              loadFailed: view.assistantNavigatorEditor.loadFailed,
            },
          }
        : {}),
      navigatorCreationTargets: view.assistantNavigatorCreationTargets(),
      creation: view.recordCreationState(),
      ...(view.listQueryController
        ? { query: assistantQueryContext(view, view.listQueryController.snapshot()) }
        : {}),
      relations: view.assistantNavigatorEditor ? [] : assistantRelationFacts(view),
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
      draft: view.editorMode !== 'view',
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

/** A bounded preview of the same protected list projection used by query.describe. */
function assistantQueryContext(view: ModulePageSessionView, snapshot: RecordQueryListQuerySnapshot) {
  const result = assistantQueryResult(assistantQuerySnapshot(view, snapshot));
  const preview = pageAssistantCatalog(result.status === 'ready' ? result.rows : [], 0, 4_000);
  return {
    mode: result.mode,
    status: result.status,
    appliedQuickSearch: result.appliedQuickSearch,
    standardQuery: result.standardQuery,
    pageNum: result.pageNum,
    pageSize: result.pageSize,
    total: result.total,
    totalKnown: result.totalKnown,
    columns: result.columns,
    rows: preview.items,
    truncated:
      result.truncated || preview.page.nextOffset !== null || preview.page.oversizedIndexes.length > 0,
    detailsCapability: 'query.describe',
  };
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
