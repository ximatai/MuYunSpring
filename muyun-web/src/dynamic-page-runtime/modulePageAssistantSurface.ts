import type { AssistantSurfaceContext } from '@muyun/web-contracts';
import {
  type AssistantCapability,
  type AssistantCapabilityExecutionContext,
  type AssistantSurface,
  type AssistantTurnRequester,
} from '@muyun/web-core';
import {
  decodeDateTimeLocalEditorValue,
  decodeNumberEditorValue,
  resolveRecordFormFieldState,
  type ReferencePickerCandidate,
  type RecordFormFieldState,
  type RecordFormFieldValue,
} from '@muyun/platform-components';
import type { ModulePageSessionView } from './useModulePageSession';
import { assistantEditableRecordIds, hasActiveRecordEditor } from './assistantRecordEditorPolicy';
import { modulePageScopeCapabilities, type ModulePageAssistantTenantScope } from './modulePageAssistantScope';

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
  return `${modulePageAssistantInteractionRevision(view)}:${view.listQueryController?.revision() ?? '-'}`;
}

export function modulePageAssistantInteractionRevision(view: ModulePageSessionView): string {
  return JSON.stringify({
    page: view.assistantContextRevision,
    navigators: Object.entries(view.selectedNavigatorRecords ?? {})
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, record]) => [key, record?.id == null ? null : String(record.id)]),
    query: view.listQueryController?.interactionRevision?.() ?? null,
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
  const capabilities = (): AssistantCapability[] => [
    ...contributedCapabilities(),
    ...modulePageScopeCapabilities(view, tenantScope),
    ...(view.listQueryController ? queryCapabilities(view) : []),
    ...recordEditorCapabilities(view),
    formDescribeCapability(view),
    ...(hasEditableDraft(view) ? [formPatchCapability(view)] : []),
    ...referenceCapabilities(view, referenceSelections),
  ];
  return {
    describe: () => surfaceContext(view),
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
        view.updateDraftReference(fieldName, candidate);
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
        view.updateDraftReference(current.fieldName, current.candidate);
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
  if (view.context.can('create') === true) {
    capabilities.push({
      descriptor: {
        code: 'record.start-create',
        description: '打开当前模块的标准新增表单并建立未保存草稿；需要新建单据或记录时使用。它不会保存。',
        inputSchema: emptyObjectSchema(),
      },
      parseInput: parseEmptyObject,
      async execute(_input, context) {
        const commit = await view.prepareAssistantCreate();
        return context.applyEffect(commit);
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
        return context.applyEffect(commit);
      },
    });
  }
  return capabilities;
}

function hasEditableDraft(view: ModulePageSessionView) {
  return hasActiveRecordEditor(view.editorMode, view.editingRecord);
}

function queryCapabilities(view: ModulePageSessionView): AssistantCapability[] {
  const controller = view.listQueryController!;
  const snapshot = controller.snapshot();
  return [
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
              context.applyEffect(() => {
                pending = controller.applyQuickSearch(keyword);
              });
              return pending;
            },
          } satisfies AssistantCapability,
        ]
      : []),
    {
      descriptor: {
        code: 'query.describe',
        description: '读取当前标准列表的查询状态和可见结果页；它不会筛选记录，仅在需要了解当前结果时使用。',
        inputSchema: emptyObjectSchema(),
      },
      parseInput: parseEmptyObject,
      async execute() {
        return controller.snapshot();
      },
    },
  ];
}

function surfaceContext(view: ModulePageSessionView): AssistantSurfaceContext {
  return {
    surface: 'module-page',
    title: view.modulePageTitle,
    facts: {
      moduleAlias: view.context.moduleAlias,
      editorMode: view.editorMode,
      selectedRecordId: recordIdentity(view.selectedRecord),
      editing: hasEditableDraft(view),
      dirty: view.detailDirty,
    },
  };
}

function formDescribeCapability(view: ModulePageSessionView): AssistantCapability<Record<string, never>> {
  return {
    descriptor: {
      code: 'form.describe',
      description: 'Describe visible form fields and whether a draft is currently editable',
      inputSchema: emptyObjectSchema(),
    },
    parseInput: parseEmptyObject,
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
            options: assistantOptions(field),
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
  return {
    descriptor: {
      code: 'form.patch-draft',
      description:
        'Atomically patch one or more assistant-writable fields in the current unsaved form draft through the standard field pipeline. It does not save.',
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
              properties: { fieldName: { type: 'string', minLength: 1 }, value: {} },
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
      validateDraftChanges(view, input.changes);
      context.applyEffect(() => {
        view.updateDraftFields(validateDraftChanges(view, input.changes));
      });
      return { changedFields: input.changes.map(({ fieldName }) => fieldName) };
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
  return { fieldName: input.fieldName.trim(), value: input.value };
}

function validateDraftChanges(view: ModulePageSessionView, changes: AssistantDraftChange[]) {
  return changes.map(({ fieldName, value }) => {
    const field = formFieldState(view, fieldName);
    if (!field || !field.visible || field.readOnly || !isAssistantWritableField(field)) {
      throw new Error(`Form field is not editable by the assistant: ${fieldName}`);
    }
    return { fieldName, value: assistantFieldValue(field, value) };
  });
}

function assistantCurrentValue(
  view: ModulePageSessionView,
  field: RecordFormFieldState,
  budget: { remaining: number; truncated: boolean },
) {
  if (isSensitiveField(field) || field.reference || field.fileReference) return undefined;
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
  return field.fieldControl?.alias === 'password';
}

function isAssistantWritableField(field: RecordFormFieldState) {
  if (isSensitiveField(field) || field.reference || field.fileReference) return false;
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
    !field.readOnly &&
    field.reference?.cardinality === 'ONE' &&
    field.reference.pickerMode !== 'TREE' &&
    field.pickerConfig?.scopedTree === undefined &&
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

function emptyObjectSchema() {
  return { type: 'object', additionalProperties: false };
}

function parseEmptyObject(input: unknown): Record<string, never> {
  if (input === undefined || (isRecord(input) && Object.keys(input).length === 0)) return {};
  throw new Error('capability input must be an empty object');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function recordIdentity(record: { id?: unknown; version?: unknown } | undefined) {
  if (record?.id === undefined || record.id === null) return undefined;
  return { id: String(record.id), version: record.version };
}
