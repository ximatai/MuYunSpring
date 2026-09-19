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
  type RecordFormFieldState,
  type RecordFormFieldValue,
} from '@muyun/platform-components';
import type { ModulePageSessionView } from './useModulePageSession';
import { assistantEditableRecordIds, hasActiveRecordEditor } from './assistantRecordEditorPolicy';

export function modulePageAssistantContextRevision(view: ModulePageSessionView): string {
  return `${view.assistantContextRevision}:${view.listQueryController?.revision() ?? '-'}`;
}

export function createModulePageAssistantSurface(
  view: ModulePageSessionView,
  requestTurn: AssistantTurnRequester,
  contributedCapabilities: () => AssistantCapability[] = () => [],
): AssistantSurface {
  const capabilities = (): AssistantCapability[] => [
    ...contributedCapabilities(),
    pageDescribeCapability(view),
    ...(view.listQueryController ? queryCapabilities(view) : []),
    ...recordEditorCapabilities(view),
    formDescribeCapability(view),
    ...(hasEditableDraft(view) ? [formPatchCapability(view)] : []),
  ];
  return {
    describe: () => surfaceContext(view),
    capabilities,
    requestTurn,
  };
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

function pageDescribeCapability(view: ModulePageSessionView): AssistantCapability<Record<string, never>> {
  return {
    descriptor: {
      code: 'page.describe',
      description: 'Describe the active standard page without reading the DOM',
      inputSchema: emptyObjectSchema(),
    },
    parseInput: parseEmptyObject,
    async execute() {
      return surfaceContext(view);
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
      return {
        editorMode: view.editorMode,
        editable: hasEditableDraft(view),
        fields: formFieldStates(view)
          .filter((field) => field.visible && !isSensitiveField(field))
          .map((field) => ({
            fieldName: field.fieldName,
            label: field.label,
            required: field.required,
            readOnly: field.readOnly,
            valueType: field.valueType,
            controlType: field.controlType,
            assistantWritable: hasEditableDraft(view) && isAssistantWritableField(field),
            options: assistantOptions(field),
          })),
      };
    },
  };
}

function formPatchCapability(
  view: ModulePageSessionView,
): AssistantCapability<{ fieldName: string; value: unknown }> {
  return {
    descriptor: {
      code: 'form.patch-draft',
      description:
        'Patch one assistant-writable field in the current unsaved form draft through the standard field pipeline',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['fieldName', 'value'],
        properties: {
          fieldName: { type: 'string', minLength: 1 },
          value: {},
        },
      },
    },
    parseInput(input) {
      if (!isRecord(input) || typeof input.fieldName !== 'string' || !input.fieldName.trim()) {
        throw new Error('form.patch-draft requires a fieldName and value');
      }
      if (!Object.hasOwn(input, 'value')) throw new Error('form.patch-draft requires a fieldName and value');
      return { fieldName: input.fieldName.trim(), value: input.value };
    },
    async execute(input, context) {
      if (!hasEditableDraft(view)) throw new Error('No editable form draft is active');
      const field = formFieldState(view, input.fieldName);
      if (!field || !field.visible || field.readOnly || !isAssistantWritableField(field)) {
        throw new Error(`Form field is not editable by the assistant: ${input.fieldName}`);
      }
      assistantFieldValue(field, input.value);
      context.applyEffect(() => {
        const current = formFieldState(view, input.fieldName);
        if (!current || !current.visible || current.readOnly || !isAssistantWritableField(current)) {
          throw new Error(`Form field is no longer editable by the assistant: ${input.fieldName}`);
        }
        view.updateDraftField(input.fieldName, assistantFieldValue(current, input.value));
      });
      return { changedField: input.fieldName };
    },
  };
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
