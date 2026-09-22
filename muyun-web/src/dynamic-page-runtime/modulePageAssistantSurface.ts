import type { AssistantSurfaceContext } from '@muyun/web-contracts';
import {
  type AssistantCapability,
  type AssistantCapabilityExecutionContext,
  type AssistantSurface,
  type AssistantTurnRequester,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
} from '@muyun/web-core';
import {
  decodeDateTimeLocalEditorValue,
  decodeNumberEditorValue,
  resolveRecordFormFieldState,
  type ReferencePickerCandidate,
  type RecordFormFieldState,
  type RecordFormFieldValue,
  type RecordQueryListQuerySnapshot,
} from '@muyun/platform-components';
import { assistantQueryResult, createAssistantQueryCapabilities } from './assistantQueryCapabilities';
import type { ModulePageSessionView } from './useModulePageSession';
import { assistantEditableRecordIds, hasActiveRecordEditor } from './assistantRecordEditorPolicy';
import {
  modulePageScopeCapabilities,
  type ModulePageAssistantTenantScope,
  type AssistantScopeCandidate,
} from './modulePageAssistantScope';

const MAX_ASSISTANT_FORM_CURRENT_VALUE_CHARS = 8_000;
const MAX_ASSISTANT_REFERENCE_OPTIONS = 10;

interface AssistantReferenceSelection {
  fieldName: string;
  contextRevision: string;
  searchRevision: number;
  candidate: ReferencePickerCandidate;
}

interface AssistantReferenceSelectionState {
  selections: Map<string, AssistantReferenceSelection>;
  searchRevision: number;
}

export function modulePageAssistantContextRevision(view: ModulePageSessionView): string {
  const querySnapshot = view.listQueryController?.snapshot();
  return JSON.stringify({
    page: view.assistantContextRevision,
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
    query: view.listQueryController?.interactionRevision?.() ?? '0',
  });
}

export function createModulePageAssistantSurface(
  view: ModulePageSessionView,
  requestTurn: AssistantTurnRequester,
  contributedCapabilities: () => AssistantCapability[] = () => [],
  tenantScope?: ModulePageAssistantTenantScope,
): AssistantSurface {
  const referenceSelections: AssistantReferenceSelectionState = {
    selections: new Map(),
    searchRevision: 0,
  };
  const scopeCandidates = new Map<string, AssistantScopeCandidate>();
  const capabilities = (): AssistantCapability[] => [
    ...contributedCapabilities(),
    ...modulePageScopeCapabilities(view, tenantScope, scopeCandidates),
    ...(view.listQueryController ? queryCapabilities(view) : []),
    ...(view.treeQueryController ? treeQueryCapabilities(view) : []),
    ...recordEditorCapabilities(view),
    ...(hasEditableDraft(view) ? [formDescribeCapability(view), formPatchCapability(view)] : []),
    ...referenceCapabilities(view, referenceSelections),
  ];
  return {
    describe: () => surfaceContext(view, tenantScope),
    capabilities,
    requestTurn,
  };
}

function referenceCapabilities(
  view: ModulePageSessionView,
  state: AssistantReferenceSelectionState,
): AssistantCapability[] {
  if (!hasEditableDraft(view)) return [];
  const fieldNames = assistantReferenceFields(view).map(({ fieldName }) => fieldName);
  if (fieldNames.length === 0) return [];
  return [
    referenceResolveAndPatchCapability(view, state, fieldNames),
    referenceSearchCapability(view, state, fieldNames),
    referencePatchCapability(view, state),
  ];
}

function referenceResolveAndPatchCapability(
  view: ModulePageSessionView,
  state: AssistantReferenceSelectionState,
  fieldNames: string[],
): AssistantCapability<{ fieldName: string; title: string }> {
  return {
    descriptor: {
      code: 'reference.resolve-and-patch',
      description:
        '按业务名称检索当前表单的单值引用；仅当标准引用源返回唯一且名称精确匹配的可用记录时回填草稿。它不会保存。应优先使用；无法确定时再搜索候选。',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['fieldName', 'title'],
        properties: {
          fieldName: { type: 'string', enum: fieldNames },
          title: { type: 'string', minLength: 1, maxLength: 500 },
        },
      },
    },
    parseInput(input) {
      if (
        !isRecord(input) ||
        typeof input.fieldName !== 'string' ||
        !fieldNames.includes(input.fieldName) ||
        typeof input.title !== 'string' ||
        !input.title.trim() ||
        input.title.length > 500
      ) {
        throw new Error('reference.resolve-and-patch requires a declared fieldName and title');
      }
      return { fieldName: input.fieldName, title: input.title.trim() };
    },
    async execute({ fieldName, title }, context) {
      const field = assistantReferenceField(view, fieldName);
      if (!field?.pickerConfig?.provider) throw new Error(`Reference field is not available: ${fieldName}`);
      const searchRevision = ++state.searchRevision;
      const page = await field.pickerConfig.provider.searchPage({
        keyword: title,
        pageNum: 1,
        pageSize: MAX_ASSISTANT_REFERENCE_OPTIONS,
        scope: { selections: [] },
      });
      if (!context.isCurrent() || state.searchRevision !== searchRevision) {
        throw new Error('Reference resolution is no longer current; resolve again');
      }
      if (page.navigation?.length) {
        throw new Error('Reference field requires scoped navigation and is not available to the assistant');
      }
      const candidate = page.records[0];
      if (
        page.total !== 1 ||
        page.records.length !== 1 ||
        !candidate ||
        !isAssistantSelectableReference(candidate) ||
        normalizeReferenceTitle(candidate.title) !== normalizeReferenceTitle(title)
      ) {
        throw new Error('Reference title is not a unique exact match; search reference options');
      }
      context.applyEffect(() => {
        if (
          state.searchRevision !== searchRevision ||
          !context.isCurrent() ||
          !assistantReferenceField(view, fieldName)
        ) {
          throw new Error('Reference resolution is no longer current; resolve again');
        }
        view.updateDraftReference(fieldName, candidate, 'assistant');
        state.selections.clear();
      });
      return { changedField: fieldName, selectedTitle: candidate.title.slice(0, 500) };
    },
  };
}

function referenceSearchCapability(
  view: ModulePageSessionView,
  state: AssistantReferenceSelectionState,
  fieldNames: string[],
): AssistantCapability<{ fieldName: string; keyword: string }> {
  return {
    descriptor: {
      code: 'reference.search-options',
      description:
        'Search the current form reference candidates through its authorized picker source. Use the returned opaque selectionKey; never invent an internal ID.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['fieldName', 'keyword'],
        properties: {
          fieldName: { type: 'string', enum: fieldNames },
          keyword: { type: 'string', maxLength: 500 },
        },
      },
    },
    parseInput(input) {
      if (
        !isRecord(input) ||
        typeof input.fieldName !== 'string' ||
        !fieldNames.includes(input.fieldName) ||
        typeof input.keyword !== 'string' ||
        input.keyword.length > 500
      ) {
        throw new Error('reference.search-options requires a declared fieldName and keyword');
      }
      return { fieldName: input.fieldName, keyword: input.keyword };
    },
    async execute({ fieldName, keyword }, context) {
      const field = assistantReferenceField(view, fieldName);
      if (!field?.pickerConfig?.provider) throw new Error(`Reference field is not available: ${fieldName}`);
      const searchRevision = ++state.searchRevision;
      const page = await field.pickerConfig.provider.searchPage({
        keyword,
        pageNum: 1,
        pageSize: MAX_ASSISTANT_REFERENCE_OPTIONS,
        scope: { selections: [] },
      });
      if (!context.isCurrent() || state.searchRevision !== searchRevision) {
        throw new Error('Reference search is no longer current; search again');
      }
      if (page.navigation?.length) {
        throw new Error('Reference field requires scoped navigation and is not available to the assistant');
      }
      const contextRevision = modulePageAssistantContextRevision(view);
      const options = page.records
        .filter((candidate) => isAssistantSelectableReference(candidate))
        .slice(0, MAX_ASSISTANT_REFERENCE_OPTIONS)
        .map((candidate) => {
          const selectionKey = crypto.randomUUID();
          return {
            selectionKey,
            selection: { fieldName, contextRevision, searchRevision, candidate },
            title: candidate.title.slice(0, 500),
          };
        });
      context.commitInternalState(() => {
        if (state.searchRevision !== searchRevision) {
          throw new Error('Reference search is no longer current; search again');
        }
        state.selections.clear();
        for (const option of options) {
          state.selections.set(option.selectionKey, option.selection);
        }
      });
      const projectedOptions = options.map(({ selectionKey, title }) => ({ selectionKey, title }));
      return {
        fieldName,
        options: projectedOptions,
        total: page.total,
        truncated: page.total > projectedOptions.length,
      };
    },
  };
}

function referencePatchCapability(
  view: ModulePageSessionView,
  state: AssistantReferenceSelectionState,
): AssistantCapability<{ selectionKey: string }> {
  return {
    descriptor: {
      code: 'reference.patch-draft',
      description:
        'Apply one previously searched opaque reference selection to the current unsaved form draft. It does not save.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['selectionKey'],
        properties: { selectionKey: { type: 'string', minLength: 1 } },
      },
    },
    parseInput(input) {
      if (!isRecord(input) || typeof input.selectionKey !== 'string' || !input.selectionKey) {
        throw new Error('reference.patch-draft requires a selectionKey returned by candidate search');
      }
      return { selectionKey: input.selectionKey };
    },
    async execute({ selectionKey }, context) {
      const selection = state.selections.get(selectionKey);
      if (
        !selection ||
        selection.contextRevision !== modulePageAssistantContextRevision(view) ||
        selection.searchRevision !== state.searchRevision ||
        !assistantReferenceField(view, selection.fieldName)
      ) {
        throw new Error('Reference selection is no longer available; search again');
      }
      context.applyEffect(() => {
        const current = state.selections.get(selectionKey);
        if (
          !current ||
          current.contextRevision !== modulePageAssistantContextRevision(view) ||
          current.searchRevision !== state.searchRevision ||
          !assistantReferenceField(view, current.fieldName)
        ) {
          throw new Error('Reference selection is no longer available; search again');
        }
        view.updateDraftReference(current.fieldName, current.candidate, 'assistant');
        state.selections.clear();
      });
      return { changedField: selection.fieldName, selectedTitle: selection.candidate.title.slice(0, 500) };
    },
  };
}

function isAssistantSelectableReference(candidate: ReferencePickerCandidate) {
  return (
    candidate.disabled !== true && candidate.unavailable !== true && candidate.identifierFallback !== true
  );
}

function normalizeReferenceTitle(value: string) {
  return value.trim().toLowerCase();
}

function recordEditorCapabilities(view: ModulePageSessionView): AssistantCapability[] {
  if (view.editorMode !== 'view' || view.detailLoading || view.detailLoadFailed) return [];
  const querySnapshot = view.listQueryController?.snapshot();
  if (querySnapshot?.mode === 'recycleBin') return [];
  const capabilities: AssistantCapability[] = [];
  if (view.recordCreationState().ready) {
    capabilities.push({
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
          throw new Error('record.start-edit requires a recordId from the current page');
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
                throw new Error('query.apply-quick-search requires a keyword');
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
          throw new Error('tree.select-record requires a selectionKey from tree.describe');
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
      ...(tenantScope?.tenantScopeExplorerVisible.value
        ? { tenant: tenantScope.selected.value ? recordTitle(tenantScope.selected.value) : null }
        : {}),
      ...(navigatorScopes.length > 0 ? { navigatorScopes } : {}),
    },
  };
}

function recordTitle(record: Record<string, unknown>) {
  const title = record.title ?? record.name ?? record.code ?? record.alias;
  return title == null ? undefined : String(title).slice(0, 500);
}

function formDescribeCapability(view: ModulePageSessionView): AssistantCapability<Record<string, never>> {
  return {
    descriptor: {
      code: 'form.describe',
      description: 'Describe visible form fields and whether a draft is currently editable',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute() {
      const valueBudget = { remaining: MAX_ASSISTANT_FORM_CURRENT_VALUE_CHARS, truncated: false };
      const fields = formFieldStates(view)
        .filter((field) => field.visible && !isSensitiveField(field))
        .map((field) => {
          const currentValue = assistantCurrentValue(view, field, valueBudget);
          const writeMode = assistantFieldWriteMode(view, field);
          return {
            fieldName: field.fieldName,
            label: field.label,
            required: field.required,
            readOnly: field.readOnly,
            valueType: field.valueType,
            controlType: field.controlType,
            assistantWritable: writeMode !== undefined,
            ...(writeMode ? { assistantWriteMode: writeMode } : {}),
            ...(field.reference
              ? {
                  referenceCardinality: field.reference.cardinality,
                  referenceTargetModuleAlias: field.reference.targetModuleAlias,
                }
              : {}),
            ...(currentValue !== undefined ? { currentValue } : {}),
            options: field.assistantPolicy === 'DESCRIBE' ? [] : assistantOptions(field),
          };
        });
      return {
        editorMode: view.editorMode,
        editable: hasEditableDraft(view),
        currentValuesTruncated: valueBudget.truncated,
        fields,
      };
    },
  };
}

function assistantFieldWriteMode(
  view: ModulePageSessionView,
  field: RecordFormFieldState,
): 'value' | 'referenceSelection' | undefined {
  if (!hasEditableDraft(view) || !field.visible || field.readOnly) return undefined;
  if (isAssistantWritableField(field)) return 'value';
  return assistantReferenceFieldState(field) ? 'referenceSelection' : undefined;
}

interface AssistantDraftChange {
  fieldName: string;
  value: unknown;
}

function formPatchCapability(
  view: ModulePageSessionView,
): AssistantCapability<{ changes: AssistantDraftChange[] }> {
  const writableFieldNames = formFieldStates(view)
    .filter((field) => field.visible && !field.readOnly && isAssistantWritableField(field))
    .map(({ fieldName }) => fieldName);
  return {
    descriptor: {
      code: 'form.patch-draft',
      description:
        'Atomically patch values supplied or requested by the user into assistant-writable fields in the current unsaved form draft. Resolve meaning from the conversation and ask for clarification when a value or its field is ambiguous. Use the declared field types and option values. It does not save; the user reviews the draft before saving.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['changes'],
        properties: {
          changes: {
            type: 'array',
            minItems: 1,
            maxItems: 20,
            items: {
              type: 'object',
              additionalProperties: false,
              required: ['fieldName', 'value'],
              properties: {
                fieldName: { type: 'string', enum: writableFieldNames },
                value: {},
              },
            },
          },
        },
      },
    },
    parseInput(input) {
      if (!isRecord(input) || !Array.isArray(input.changes) || input.changes.length === 0) {
        throw new Error('form.patch-draft requires changes');
      }
      if (input.changes.length > 20) throw new Error('form.patch-draft accepts at most 20 changes');
      const changes = input.changes.map(parseDraftChange);
      if (new Set(changes.map(({ fieldName }) => fieldName)).size !== changes.length) {
        throw new Error('form.patch-draft field names must be unique');
      }
      return { changes };
    },
    async execute(input, context) {
      if (!hasEditableDraft(view)) throw new Error('No editable form draft is active');
      validateDraftTargets(view, input.changes);
      const validatedChanges = validateDraftChanges(view, input.changes);
      const before = draftSummaryValues(view);
      context.applyEffect(() => {
        view.updateDraftFields(validatedChanges, 'assistant');
      });
      const after = draftSummaryValues(view);
      const requested = new Set(input.changes.map(({ fieldName }) => fieldName));
      const changes = formFieldStates(view)
        .filter((field) => after.has(field.fieldName))
        .filter(
          (field) =>
            JSON.stringify(before.get(field.fieldName)) !== JSON.stringify(after.get(field.fieldName)),
        )
        .map((field) => ({
          fieldName: field.fieldName,
          label: field.label,
          source: requested.has(field.fieldName) ? 'assistant' : 'derived',
          before: before.get(field.fieldName),
          after: after.get(field.fieldName),
        }));
      const missingRequired = formFieldStates(view)
        .filter((field) => field.visible && field.required && !isSensitiveField(field))
        .filter((field) => {
          const value = view.editingRecord?.[field.fieldName];
          return (
            value === undefined ||
            value === null ||
            value === '' ||
            (Array.isArray(value) && value.length === 0)
          );
        })
        .map((field) => field.label);
      return { changedFields: [...requested], draftSummary: { saved: false, changes, missingRequired } };
    },
  };
}

function parseDraftChange(input: unknown): AssistantDraftChange {
  if (!isRecord(input) || typeof input.fieldName !== 'string' || !input.fieldName.trim()) {
    throw new Error('form.patch-draft changes require a fieldName and value');
  }
  if (!Object.hasOwn(input, 'value')) {
    throw new Error('form.patch-draft changes require a fieldName and value');
  }
  return {
    fieldName: input.fieldName.trim(),
    value: input.value,
  };
}

function validateDraftChanges(view: ModulePageSessionView, changes: AssistantDraftChange[]) {
  return changes.map(({ fieldName, value }) => {
    const field = formFieldState(view, fieldName)!;
    return { fieldName, value: assistantFieldValue(field, value) };
  });
}

function validateDraftTargets(view: ModulePageSessionView, changes: AssistantDraftChange[]) {
  for (const { fieldName } of changes) {
    const field = formFieldState(view, fieldName);
    if (!field || !field.visible || field.readOnly || !isAssistantWritableField(field)) {
      throw new Error(`Form field is not editable by the assistant: ${fieldName}`);
    }
  }
}

function assistantCurrentValue(
  view: ModulePageSessionView,
  field: RecordFormFieldState,
  budget: { remaining: number; truncated: boolean },
) {
  if (
    isSensitiveField(field) ||
    field.assistantPolicy === 'DESCRIBE' ||
    field.reference ||
    field.fileReference
  )
    return undefined;
  const value = (view.editingRecord ?? view.selectedRecord)?.[field.fieldName];
  let candidate: null | string | number | boolean | Array<string | number | boolean> | undefined;
  if (value === undefined) return undefined;
  if (value === null || typeof value === 'number' || typeof value === 'boolean') candidate = value;
  else if (typeof value === 'string') candidate = value.slice(0, 2_000);
  else if (
    Array.isArray(value) &&
    value.length <= 20 &&
    value.every((item) => typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean')
  ) {
    candidate = value.map((item) => (typeof item === 'string' ? item.slice(0, 200) : item));
  } else return undefined;
  const cost = JSON.stringify(candidate).length;
  if (cost > budget.remaining) {
    budget.truncated = true;
    return undefined;
  }
  budget.remaining -= cost;
  return candidate;
}

function isSensitiveField(field: RecordFormFieldState) {
  return field.fieldControl?.alias === 'password' || field.assistantPolicy === 'HIDDEN';
}

function isAssistantWritableField(field: RecordFormFieldState) {
  if (
    isSensitiveField(field) ||
    (field.assistantPolicy !== undefined && field.assistantPolicy !== 'READ_WRITE') ||
    field.reference ||
    field.fileReference ||
    field.fieldControl?.rendererType === 'JSON' ||
    field.valueType === 'JSON'
  )
    return false;
  if (
    field.controlType === 'select' ||
    field.controlType === 'dictionaryPicker' ||
    field.controlType === 'dictionaryRadioGroup'
  ) {
    return assistantOptions(field).length > 0;
  }
  return [
    'input',
    'numberInput',
    'dateInput',
    'dateTimeInput',
    'textarea',
    'colorPicker',
    'enabledStatus',
    'booleanStatus',
    'switch',
  ].includes(field.controlType);
}

function assistantReferenceFields(view: ModulePageSessionView) {
  return formFieldStates(view).filter((field) => assistantReferenceFieldState(field));
}

function assistantReferenceField(view: ModulePageSessionView, fieldName: string) {
  const field = formFieldState(view, fieldName);
  return field && assistantReferenceFieldState(field) ? field : undefined;
}

function assistantReferenceFieldState(field: RecordFormFieldState) {
  return (
    field.visible &&
    !isSensitiveField(field) &&
    (field.assistantPolicy === undefined || field.assistantPolicy === 'READ_WRITE') &&
    !field.readOnly &&
    field.reference?.cardinality === 'ONE' &&
    field.pickerConfig?.scopedTree?.disabled !== true &&
    field.pickerConfig?.provider !== undefined
  );
}

function assistantFieldValue(field: RecordFormFieldState, value: unknown): RecordFormFieldValue {
  if (value === null) {
    if (field.required) throw new Error(`Form field is required: ${field.fieldName}`);
    return undefined;
  }
  if (field.fieldControl?.rendererType === 'JSON') {
    if (!value || typeof value !== 'object') throw invalidFieldValue(field);
    return value as RecordFormFieldValue;
  }
  if (field.controlType === 'numberInput') {
    if (typeof value !== 'string' && typeof value !== 'number') throw invalidFieldValue(field);
    if ((field.valueType === 'LONG' || field.valueType === 'DECIMAL') && typeof value !== 'string') {
      throw invalidFieldValue(field);
    }
    const decoded = decodeNumberEditorValue(String(value), field.valueType);
    if (decoded === undefined) throw invalidFieldValue(field);
    return decoded;
  }
  if (field.controlType === 'dateInput') {
    if (typeof value !== 'string' || !isIsoDate(value)) throw invalidFieldValue(field);
    return value;
  }
  if (field.controlType === 'dateTimeInput') {
    if (typeof value !== 'string' || !isIsoLocalDateTime(value)) throw invalidFieldValue(field);
    const decoded = decodeDateTimeLocalEditorValue(value);
    if (decoded === undefined) throw invalidFieldValue(field);
    return decoded;
  }
  if (['enabledStatus', 'booleanStatus', 'switch'].includes(field.controlType)) {
    if (typeof value !== 'boolean') throw invalidFieldValue(field);
    return value;
  }
  if (
    field.controlType === 'select' ||
    field.controlType === 'dictionaryPicker' ||
    field.controlType === 'dictionaryRadioGroup'
  ) {
    return assistantOptionValue(field, value);
  }
  if (typeof value !== 'string' || value.length > 10_000) throw invalidFieldValue(field);
  return value;
}

function assistantOptionValue(field: RecordFormFieldState, value: unknown): RecordFormFieldValue {
  const allowed = new Set(assistantOptions(field).map((option) => option.value));
  if (field.optionSelectionMode === 'MULTIPLE') {
    if (!Array.isArray(value) || value.some((item) => !isOptionValue(item) || !allowed.has(item))) {
      throw invalidFieldValue(field);
    }
    return value;
  }
  if (!isOptionValue(value) || !allowed.has(value)) throw invalidFieldValue(field);
  return value;
}

function assistantOptions(field: RecordFormFieldState) {
  if (field.options?.length) {
    return field.options
      .filter((option) => option.disabled !== true)
      .map((option) => ({ value: option.value, label: option.label }));
  }
  return (field.optionItems ?? [])
    .filter((option) => option.enabled)
    .map((option) => ({ value: option.code, label: option.title }));
}

function isOptionValue(value: unknown): value is string | number {
  return typeof value === 'string' || (typeof value === 'number' && Number.isFinite(value));
}

function isIsoDate(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return (
    !Number.isNaN(date.getTime()) &&
    date.getUTCFullYear() === Number(match[1]) &&
    date.getUTCMonth() + 1 === Number(match[2]) &&
    date.getUTCDate() === Number(match[3])
  );
}

function isIsoLocalDateTime(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
  if (!match) return false;
  const parts = match.slice(1).map((part) => Number(part ?? 0));
  const [year, month, day, hour, minute, second] = parts;
  const date = new Date(year, month - 1, day, hour, minute, second);
  return (
    !Number.isNaN(date.getTime()) &&
    date.getFullYear() === year &&
    date.getMonth() + 1 === month &&
    date.getDate() === day &&
    date.getHours() === hour &&
    date.getMinutes() === minute &&
    date.getSeconds() === second
  );
}

function invalidFieldValue(field: RecordFormFieldState) {
  return new Error(`Invalid value for form field: ${field.fieldName}`);
}

function formFieldStates(view: ModulePageSessionView): RecordFormFieldState[] {
  return [...view.formFields.keys()].map((fieldName) => formFieldState(view, fieldName)!);
}

function formFieldState(view: ModulePageSessionView, fieldName: string) {
  if (!view.formFields.has(fieldName)) return undefined;
  return resolveRecordFormFieldState(fieldName, {
    fields: view.formFields,
    pickerConfigs: view.referencePickerConfigs,
    record: view.editingRecord ?? view.selectedRecord,
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function recordIdentity(record: { id?: unknown; version?: unknown } | undefined) {
  if (record?.id === undefined || record.id === null) return undefined;
  return { id: String(record.id), version: record.version };
}

function draftSummaryValues(view: ModulePageSessionView): Map<string, unknown> {
  const budget = { remaining: MAX_ASSISTANT_FORM_CURRENT_VALUE_CHARS, truncated: false };
  return new Map(
    formFieldStates(view)
      .filter((field) => field.visible && !isSensitiveField(field) && field.assistantPolicy !== 'DESCRIBE')
      .map((field) => [field.fieldName, assistantCurrentValue(view, field, budget)]),
  );
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
