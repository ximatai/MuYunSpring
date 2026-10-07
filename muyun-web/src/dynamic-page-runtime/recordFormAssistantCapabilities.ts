import type { RecordFormDraftAccess } from './recordFormDraftAccess';
import {
  assistantReadableField,
  recordFieldDisplay,
  resolvedRecordFieldDisplay,
} from './recordDisplayProjection';
import { hasActiveRecordEditor } from './assistantRecordEditorPolicy';
import {
  createUuid,
  AssistantCapabilityUsageError,
  type AssistantCapability,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
} from '@muyun/web-core';
import {
  recordFormInputConstraintError,
  decodeDateTimeLocalEditorValue,
  decodeNumberEditorValue,
  resolveRecordFormFieldState,
  resolveRecordDetailDisplayValue,
  resolveRecordEnabledStatusValue,
  type ReferencePickerCandidate,
  type RecordFormFieldState,
  type RecordFormFieldValue,
} from '@muyun/platform-components';

export function createRecordFormAssistantCapabilities(
  view: RecordFormDraftAccess,
  {
    resolveReferenceNames = true,
    canReadDetail,
    schemaDiscovery,
  }: {
    resolveReferenceNames?: boolean;
    canReadDetail?: () => boolean;
    schemaDiscovery?: AssistantCapability['schemaDiscovery'];
  } = {},
) {
  const references: AssistantReferenceSelectionState = { selections: new Map(), searchRevision: 0 };
  const canDescribe = () => hasEditableDraft(view) || canReadDetail?.() === true;
  return () => [
    ...(canDescribe()
      ? [
          {
            ...formDescribeCapability(view, resolveReferenceNames, canDescribe),
            schemaDiscovery: hasEditableDraft(view) ? undefined : schemaDiscovery,
          },
        ]
      : []),
    ...(hasEditableDraft(view)
      ? [
          { ...formPatchCapability(view), schemaDiscovery },
          ...referenceCapabilities(view, references).map((capability) => ({
            ...capability,
            ...(capability.descriptor.code === 'reference.resolve-and-patch' ? { schemaDiscovery } : {}),
          })),
        ]
      : []),
  ];
}

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

function referenceCapabilities(
  view: RecordFormDraftAccess,
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
  view: RecordFormDraftAccess,
  state: AssistantReferenceSelectionState,
  fieldNames: string[],
): AssistantCapability<{ fieldName: string; title: string; changes?: AssistantDraftChange[] }> {
  return {
    effect: 'draft',
    descriptor: {
      code: 'reference.resolve-and-patch',
      description:
        '按业务名称检索当前表单的单值引用；仅当标准引用源返回唯一且名称精确匹配的可用记录时回填草稿。可通过 changes 同次填写用户明确的普通字段值，复用标准回填和计算；不保存。应优先使用；无法确定时再搜索候选。',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['fieldName', 'title'],
        properties: {
          fieldName: { type: 'string', enum: fieldNames },
          title: { type: 'string', minLength: 1, maxLength: 500 },
          changes: draftChangesInputSchema(writableFieldNames(view)),
        },
      },
    },
    parseInput(input) {
      if (
        !isRecord(input) ||
        Object.keys(input).some((key) => !['fieldName', 'title', 'changes'].includes(key)) ||
        typeof input.fieldName !== 'string' ||
        !fieldNames.includes(input.fieldName) ||
        typeof input.title !== 'string' ||
        !input.title.trim() ||
        input.title.length > 500
      ) {
        throw new AssistantCapabilityUsageError(
          'reference.resolve-and-patch requires a declared fieldName and title',
        );
      }
      return {
        fieldName: input.fieldName,
        title: input.title.trim(),
        ...(input.changes === undefined ? {} : { changes: parseDraftChanges(input.changes) }),
      };
    },
    async execute({ fieldName, title, changes }, context) {
      if (!hasEditableDraft(view))
        throw new AssistantCapabilityUsageError('No editable form draft is active');
      if (changes) {
        validateDraftTargets(view, changes);
        validateDraftChanges(view, changes);
      }
      const revision = view.contextRevision();
      const field = assistantReferenceField(view, fieldName);
      if (!field?.pickerConfig?.provider)
        throw new AssistantCapabilityUsageError(`Reference field is not available: ${fieldName}`);
      const searchRevision = ++state.searchRevision;
      const page = await field.pickerConfig.provider.searchPage({
        keyword: title,
        pageNum: 1,
        pageSize: MAX_ASSISTANT_REFERENCE_OPTIONS,
        scope: { selections: [] },
      });
      if (
        !context.isCurrent() ||
        state.searchRevision !== searchRevision ||
        view.contextRevision() !== revision
      ) {
        throw new AssistantCapabilityUsageError('Reference resolution is no longer current; resolve again');
      }
      if (page.navigation?.length) {
        throw new AssistantCapabilityUsageError(
          'Reference field requires scoped navigation and is not available to the assistant',
        );
      }
      const candidate = page.records[0];
      if (page.total === 0 && page.records.length === 0) {
        throw new AssistantCapabilityUsageError(
          'No reference candidates matched this title in the authorized source; search with a shorter business name or an empty keyword and compare candidate titles and subtitles. This does not prove the record is absent.',
          'PRECONDITION_FAILED',
        );
      }
      if (
        page.total !== 1 ||
        page.records.length !== 1 ||
        !candidate ||
        !isAssistantSelectableReference(candidate) ||
        normalizeReferenceTitle(candidate.title) !== normalizeReferenceTitle(title)
      ) {
        throw new AssistantCapabilityUsageError(
          'Reference title is not a unique exact match; search reference options',
          'CANDIDATE_AMBIGUOUS',
        );
      }
      let validatedChanges: Parameters<RecordFormDraftAccess['updateDraftFields']>[0] | undefined;
      if (changes) {
        validateDraftTargets(view, changes);
        validatedChanges = validateDraftChanges(view, changes);
      }
      context.applyEffect(() => {
        if (
          state.searchRevision !== searchRevision ||
          !context.isCurrent() ||
          view.contextRevision() !== revision ||
          !assistantReferenceField(view, fieldName)
        ) {
          throw new AssistantCapabilityUsageError('Reference resolution is no longer current; resolve again');
        }
        if (validatedChanges)
          view.updateDraftReference(fieldName, candidate, 'assistant', validatedChanges, (record) => {
            if (String(record[fieldName] ?? '') !== candidate.id)
              throw new AssistantCapabilityUsageError('Combined values invalidate the selected reference');
            const projected: RecordFormDraftAccess = { ...view, editingRecord: record };
            validateDraftTargets(projected, changes!);
            validateDraftChanges(projected, changes!);
          });
        else view.updateDraftReference(fieldName, candidate, 'assistant');
        state.selections.clear();
      });
      return {
        ...referenceDraftResult(view, fieldName, candidate),
        ...(changes ? { changedFields: [fieldName, ...changes.map((change) => change.fieldName)] } : {}),
      };
    },
  };
}

function referenceSearchCapability(
  view: RecordFormDraftAccess,
  state: AssistantReferenceSelectionState,
  fieldNames: string[],
): AssistantCapability<{ fieldName: string; keyword: string }> {
  return {
    effect: 'read',
    changesReadState: true,
    descriptor: {
      code: 'reference.search-options',
      description:
        'Search the current form reference candidates through its authorized picker source. Compare candidate titles and subtitles before selecting. If no options match, broaden the business-name keyword or use an empty keyword; no matches do not prove absence. Use the returned opaque selectionKey; never invent an internal ID.',
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
        Object.keys(input).some((key) => !['fieldName', 'keyword'].includes(key)) ||
        typeof input.fieldName !== 'string' ||
        !fieldNames.includes(input.fieldName) ||
        typeof input.keyword !== 'string' ||
        input.keyword.length > 500
      ) {
        throw new AssistantCapabilityUsageError(
          'reference.search-options requires a declared fieldName and keyword',
        );
      }
      return { fieldName: input.fieldName, keyword: input.keyword };
    },
    async execute({ fieldName, keyword }, context) {
      const field = assistantReferenceField(view, fieldName);
      if (!field?.pickerConfig?.provider)
        throw new AssistantCapabilityUsageError(`Reference field is not available: ${fieldName}`);
      const searchRevision = ++state.searchRevision;
      const page = await field.pickerConfig.provider.searchPage({
        keyword,
        pageNum: 1,
        pageSize: MAX_ASSISTANT_REFERENCE_OPTIONS,
        scope: { selections: [] },
      });
      if (!context.isCurrent() || state.searchRevision !== searchRevision) {
        throw new AssistantCapabilityUsageError('Reference search is no longer current; search again');
      }
      if (page.navigation?.length) {
        throw new AssistantCapabilityUsageError(
          'Reference field requires scoped navigation and is not available to the assistant',
        );
      }
      const contextRevision = view.contextRevision();
      const options = page.records
        .filter((candidate) => isAssistantSelectableReference(candidate))
        .slice(0, MAX_ASSISTANT_REFERENCE_OPTIONS)
        .map((candidate) => {
          const selectionKey = createUuid();
          return {
            selectionKey,
            selection: { fieldName, contextRevision, searchRevision, candidate },
            title: candidate.title.slice(0, 500),
            ...(candidate.subtitle?.trim() ? { subtitle: candidate.subtitle.trim().slice(0, 500) } : {}),
          };
        });
      context.commitInternalState(() => {
        if (state.searchRevision !== searchRevision) {
          throw new AssistantCapabilityUsageError('Reference search is no longer current; search again');
        }
        state.selections.clear();
        for (const option of options) {
          state.selections.set(option.selectionKey, option.selection);
        }
      });
      const projectedOptions = options.map(({ selectionKey, title, subtitle }) => ({
        selectionKey,
        title,
        ...(subtitle ? { subtitle } : {}),
      }));
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
  view: RecordFormDraftAccess,
  state: AssistantReferenceSelectionState,
): AssistantCapability<{ selectionKey: string }> {
  return {
    effect: 'draft',
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
        throw new AssistantCapabilityUsageError(
          'reference.patch-draft requires a selectionKey returned by candidate search',
        );
      }
      return { selectionKey: input.selectionKey };
    },
    async execute({ selectionKey }, context) {
      const selection = state.selections.get(selectionKey);
      if (
        !selection ||
        selection.contextRevision !== view.contextRevision() ||
        selection.searchRevision !== state.searchRevision ||
        !assistantReferenceField(view, selection.fieldName)
      ) {
        throw new AssistantCapabilityUsageError(
          'Reference selection is no longer available; search again',
          'CANDIDATE_EXPIRED',
        );
      }
      context.applyEffect(() => {
        const current = state.selections.get(selectionKey);
        if (
          !current ||
          current.contextRevision !== view.contextRevision() ||
          current.searchRevision !== state.searchRevision ||
          !assistantReferenceField(view, current.fieldName)
        ) {
          throw new AssistantCapabilityUsageError(
            'Reference selection is no longer available; search again',
            'CANDIDATE_EXPIRED',
          );
        }
        view.updateDraftReference(current.fieldName, current.candidate, 'assistant');
        state.selections.clear();
      });
      return referenceDraftResult(view, selection.fieldName, selection.candidate);
    },
  };
}

/** Historical selection receipt, not a claim about the source's latest value or a saved result. */
function referenceDraftResult(
  view: RecordFormDraftAccess,
  fieldName: string,
  candidate: ReferencePickerCandidate,
) {
  const result = {
    changedField: fieldName,
    selectedTitle: candidate.title.slice(0, 500),
    ...(candidate.subtitle?.trim() ? { selectedSubtitle: candidate.subtitle.trim().slice(0, 500) } : {}),
  };
  if (String(view.editingRecord?.[fieldName] ?? '') !== candidate.id) return result;
  const mapped = new Set(
    Object.entries(candidate.affectPatch ?? {})
      .filter(
        ([name, value]) =>
          name !== fieldName && JSON.stringify(view.editingRecord?.[name]) === JSON.stringify(value),
      )
      .map(([name]) => name),
  );
  if (!mapped.size) return result;
  let remaining = 4_000;
  const fields = recordFormAssistantFacts(view)
    .fields.flatMap((fact) => {
      if (!mapped.has(fact.fieldName) || !Object.hasOwn(fact, 'currentValue')) return [];
      const state = formFieldState(view, fact.fieldName);
      if (
        !state ||
        !assistantReadableField(state) ||
        state.reference ||
        state.readOnly ||
        state.calculationPending
      )
        return [];
      if (JSON.stringify(fact.currentValue) !== JSON.stringify(view.editingRecord?.[fact.fieldName]))
        return [];
      const field = { fieldName: fact.fieldName, label: fact.label, value: fact.currentValue };
      const size = JSON.stringify(field).length;
      if (size > remaining) return [];
      remaining -= size;
      return [field];
    })
    .slice(0, 8);
  return fields.length
    ? { ...result, selectionSnapshot: { source: 'authorized-reference-selection', saved: false, fields } }
    : result;
}

function isAssistantSelectableReference(candidate: ReferencePickerCandidate) {
  return (
    candidate.disabled !== true && candidate.unavailable !== true && candidate.identifierFallback !== true
  );
}

function normalizeReferenceTitle(value: string) {
  return value.trim().toLowerCase();
}

function hasEditableDraft(view: RecordFormDraftAccess) {
  return hasActiveRecordEditor(view.editorMode, view.editingRecord);
}

function formDescribeCapability(
  view: RecordFormDraftAccess,
  resolveReferenceNames: boolean,
  canDescribe: () => boolean,
): AssistantCapability<Record<string, never>> {
  return {
    effect: 'read',
    descriptor: {
      code: 'form.describe',
      description:
        'Read the current visible detail or draft fields, resolving selected reference names when needed. Reuse facts.currentForm or the returned row facts when they already contain the needed fields and values; read only missing or truncated facts. Read-only details do not create an editing draft.',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute(_input, context) {
      if (!canDescribe()) throw new AssistantCapabilityUsageError('详情或草稿已不可读取');
      const revision = view.contextRevision();
      const record = { ...(view.editingRecord ?? view.selectedRecord ?? {}) };
      const visibleFields = formFieldStates(view).filter(
        (field) => field.visible && !isSensitiveField(field),
      );
      // Persisted details already carry authorized projections; candidate eligibility is a draft concern.
      const resolveDraftReferences = resolveReferenceNames && hasEditableDraft(view);
      const resolved = new Map<string, { display: string; unavailable?: boolean }>();
      let remainingReferences = 20;
      let referencesTruncated = false;
      await Promise.all(
        visibleFields.map(async (field) => {
          if (
            !resolveDraftReferences ||
            !field.reference ||
            field.assistantPolicy === 'DESCRIBE' ||
            field.fileReference ||
            field.calculationPending
          )
            return;
          const value = record[field.fieldName];
          const count = value == null || value === '' ? 0 : Array.isArray(value) ? value.length : 1;
          if (field.pickerConfig?.provider && count > remainingReferences) {
            referencesTruncated = true;
            resolved.set(field.fieldName, {
              display: '已选择（名称读取已截断，请在页面核实）',
              unavailable: true,
            });
            return;
          }
          if (field.pickerConfig?.provider) remainingReferences -= count;
          try {
            resolved.set(field.fieldName, { display: await resolvedRecordFieldDisplay(field, record) });
          } catch {
            // Display failure is not permission to reselect, reveal IDs or forward transport details.
            resolved.set(field.fieldName, { display: '已选择（名称暂不可用）', unavailable: true });
          }
        }),
      );
      if (!context.isCurrent() || !canDescribe() || revision !== view.contextRevision())
        throw new AssistantCapabilityUsageError('表单或范围已变化，请重新读取');
      return {
        ...recordFormAssistantFacts(view, resolved, referencesTruncated),
        relations: view.relations?.() ?? [],
      };
    },
  };
}

/** Same field projection for live context and explicit reads; never queries reference sources. */
export function recordFormAssistantFacts(
  view: RecordFormDraftAccess,
  resolved = new Map<string, { display: string; unavailable?: boolean }>(),
  referencesTruncated = false,
) {
  const valueBudget = { remaining: MAX_ASSISTANT_FORM_CURRENT_VALUE_CHARS, truncated: referencesTruncated };
  const fields = formFieldStates(view)
    .filter((field) => field.visible && !isSensitiveField(field))
    .map((field) => {
      const observedDisplay = view.referenceDisplay?.(field.fieldName);
      const reference =
        resolved.get(field.fieldName) ?? (observedDisplay ? { display: observedDisplay } : undefined);
      const value = view.editingRecord?.[field.fieldName];
      const referenceNeedsRead =
        field.reference &&
        hasEditableDraft(view) &&
        !reference &&
        value != null &&
        value !== '' &&
        (!Array.isArray(value) || value.length > 0);
      const fieldBudget = { remaining: valueBudget.remaining, truncated: false };
      const currentValue = assistantCurrentValue(
        view,
        field,
        fieldBudget,
        referenceNeedsRead ? '已选择（名称按需核实）' : reference?.display,
      );
      valueBudget.remaining = fieldBudget.remaining;
      valueBudget.truncated ||= fieldBudget.truncated;
      const writeMode = assistantFieldWriteMode(view, field);
      return {
        fieldName: field.fieldName,
        label: field.label,
        required: field.required,
        readOnly: field.readOnly,
        ...(field.calculationPending ? { calculationPending: true } : {}),
        valueType: field.valueType,
        ...(field.inputRequirements ? { inputRequirements: field.inputRequirements } : {}),
        ...(assistantValueHint(field) ? { valueHint: assistantValueHint(field) } : {}),
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
        ...(fieldBudget.truncated ? { currentValueTruncated: true } : {}),
        ...(reference?.unavailable || referenceNeedsRead ? { currentValueUnavailable: true } : {}),
        options: field.assistantPolicy === 'DESCRIBE' ? [] : assistantOptions(field),
      };
    });
  return {
    editorMode: view.editorMode,
    editable: hasEditableDraft(view),
    currentValuesTruncated: valueBudget.truncated,
    fields,
  };
}

function assistantFieldWriteMode(
  view: RecordFormDraftAccess,
  field: RecordFormFieldState,
): 'value' | 'referenceSelection' | undefined {
  if (!hasEditableDraft(view) || !field.visible || field.readOnly) return undefined;
  if (isAssistantWritableField(field)) return 'value';
  return assistantReferenceFieldState(field) ? 'referenceSelection' : undefined;
}

interface AssistantDraftResult {
  changedFields: string[];
  draftSummary: {
    saved: boolean;
    truncated?: boolean;
    changes: Array<{ fieldName: string; label: string; source: string; before?: unknown; after?: unknown }>;
    missingRequired: string[];
  };
}

interface AssistantDraftChange {
  fieldName: string;
  value: unknown;
}

function formPatchCapability(
  view: RecordFormDraftAccess,
): AssistantCapability<{ changes: AssistantDraftChange[] }, AssistantDraftResult> {
  return {
    effect: 'draft',
    present({ draftSummary }) {
      const display = (value: unknown, fieldName: string) => {
        const field = formFieldStates(view).find((item) => item.fieldName === fieldName);
        // Reference summaries already contain authorized display text, not reference IDs.
        if (field && !field.reference)
          return resolveRecordDetailDisplayValue(
            field,
            { [fieldName]: value },
            { emptyText: '空', draft: true },
          ).slice(0, 200);
        return value === undefined || value === null || value === '' ? '空' : String(value).slice(0, 200);
      };
      return {
        title: '草稿变更（尚未保存）',
        lines: [
          ...draftSummary.changes.map(
            (change) =>
              `${change.label}${change.source === 'derived' ? '（联动）' : ''}：${display(change.before, change.fieldName)} → ${display(change.after, change.fieldName)}`,
          ),
          ...(draftSummary.missingRequired.length
            ? [`待填写：${draftSummary.missingRequired.join('、')}`]
            : []),
        ],
      };
    },
    descriptor: {
      code: 'form.patch-draft',
      description:
        'Atomically patch values supplied or requested by the user into assistant-writable fields in the current unsaved form draft. Resolve meaning from the conversation and ask for clarification when a value or its field is ambiguous. Use the declared field types and option values. LONG and DECIMAL values must be JSON strings to preserve precision (for example "100.00"); dates use YYYY-MM-DD strings. It does not save; the user reviews the draft before saving.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['changes'],
        properties: {
          changes: draftChangesInputSchema(writableFieldNames(view)),
        },
      },
    },
    parseInput(input) {
      if (!isRecord(input)) throw new AssistantCapabilityUsageError('form.patch-draft requires changes');
      return { changes: parseDraftChanges(input.changes) };
    },
    async execute(input, context) {
      if (!hasEditableDraft(view))
        throw new AssistantCapabilityUsageError('No editable form draft is active');
      validateDraftTargets(view, input.changes);
      const validatedChanges = validateDraftChanges(view, input.changes);
      const before = draftSummaryValues(view);
      context.applyEffect(() => {
        view.updateDraftFields(validatedChanges, 'assistant');
      });
      const after = draftSummaryValues(view);
      const requested = new Set(input.changes.map(({ fieldName }) => fieldName));
      const changes = formFieldStates(view)
        .filter((field) => after.values.has(field.fieldName))
        .filter(
          (field) =>
            JSON.stringify(before.values.get(field.fieldName)) !==
            JSON.stringify(after.values.get(field.fieldName)),
        )
        .map((field) => ({
          fieldName: field.fieldName,
          label: field.label,
          source: requested.has(field.fieldName) ? 'assistant' : 'derived',
          before: before.values.get(field.fieldName),
          after: after.values.get(field.fieldName),
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
      return {
        changedFields: [...requested],
        draftSummary: {
          saved: false,
          changes,
          missingRequired,
          ...(before.truncated || after.truncated ? { truncated: true } : {}),
        },
      };
    },
  };
}

function writableFieldNames(view: RecordFormDraftAccess) {
  return formFieldStates(view)
    .filter((field) => field.visible && !field.readOnly && isAssistantWritableField(field))
    .map(({ fieldName }) => fieldName);
}

function draftChangesInputSchema(fieldNames: string[]) {
  return {
    type: 'array',
    minItems: 1,
    maxItems: 20,
    items: {
      type: 'object',
      additionalProperties: false,
      required: ['fieldName', 'value'],
      properties: { fieldName: { type: 'string', enum: fieldNames }, value: {} },
    },
  };
}

function parseDraftChanges(input: unknown) {
  if (!Array.isArray(input) || !input.length)
    throw new AssistantCapabilityUsageError('form.patch-draft requires changes');
  if (input.length > 20)
    throw new AssistantCapabilityUsageError('form.patch-draft accepts at most 20 changes');
  const changes = input.map(parseDraftChange);
  if (new Set(changes.map(({ fieldName }) => fieldName)).size !== changes.length)
    throw new AssistantCapabilityUsageError('form.patch-draft field names must be unique');
  return changes;
}

function parseDraftChange(input: unknown): AssistantDraftChange {
  if (!isRecord(input) || typeof input.fieldName !== 'string' || !input.fieldName.trim()) {
    throw new AssistantCapabilityUsageError('form.patch-draft changes require a fieldName and value');
  }
  if (!Object.hasOwn(input, 'value')) {
    throw new AssistantCapabilityUsageError('form.patch-draft changes require a fieldName and value');
  }
  return {
    fieldName: input.fieldName.trim(),
    value: input.value,
  };
}

function validateDraftChanges(view: RecordFormDraftAccess, changes: AssistantDraftChange[]) {
  return changes.map(({ fieldName, value }) => {
    const field = formFieldState(view, fieldName)!;
    return { fieldName, value: assistantFieldValue(field, value) };
  });
}

function validateDraftTargets(view: RecordFormDraftAccess, changes: AssistantDraftChange[]) {
  for (const { fieldName } of changes) {
    const field = formFieldState(view, fieldName);
    if (!field || !field.visible || field.readOnly || !isAssistantWritableField(field)) {
      throw new AssistantCapabilityUsageError(`Form field is not editable by the assistant: ${fieldName}`);
    }
  }
}

function assistantCurrentValue(
  view: RecordFormDraftAccess,
  field: RecordFormFieldState,
  budget: { remaining: number; truncated: boolean },
  resolvedReferenceDisplay?: string,
) {
  if (isSensitiveField(field) || field.assistantPolicy === 'DESCRIBE' || field.fileReference)
    return undefined;
  const value = field.calculationPending
    ? recordFieldDisplay(field, view.editingRecord ?? view.selectedRecord ?? {})
    : field.reference
      ? (resolvedReferenceDisplay ??
        recordFieldDisplay(field, view.editingRecord ?? view.selectedRecord ?? {}))
      : field.controlType === 'enabledStatus' && hasEditableDraft(view)
        ? resolveRecordEnabledStatusValue(view.editingRecord?.[field.fieldName])
        : (view.editingRecord ?? view.selectedRecord)?.[field.fieldName];
  let candidate: null | string | number | boolean | Array<string | number | boolean> | undefined;
  if (value === undefined) return undefined;
  if (value === null || typeof value === 'number' || typeof value === 'boolean') candidate = value;
  else if (typeof value === 'string') {
    const limit = field.reference ? 500 : 2_000;
    budget.truncated ||= value.length > limit;
    candidate = value.slice(0, limit);
  } else if (Array.isArray(value) && value.length > 20) {
    budget.truncated = true;
    return undefined;
  } else if (
    Array.isArray(value) &&
    value.length <= 20 &&
    value.every((item) => typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean')
  ) {
    budget.truncated ||= value.some((item) => typeof item === 'string' && item.length > 200);
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

export function isSensitiveField(field: RecordFormFieldState) {
  return field.fieldControl?.alias === 'password' || field.assistantPolicy === 'HIDDEN';
}

function isAssistantWritableField(field: RecordFormFieldState) {
  if (
    isSensitiveField(field) ||
    (field.assistantPolicy !== undefined && field.assistantPolicy !== 'READ_WRITE') ||
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

function assistantReferenceFields(view: RecordFormDraftAccess) {
  return formFieldStates(view).filter((field) => assistantReferenceFieldState(field));
}

function assistantReferenceField(view: RecordFormDraftAccess, fieldName: string) {
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
    if (field.required) throw new AssistantCapabilityUsageError(`Form field is required: ${field.fieldName}`);
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
  const constraintError = recordFormInputConstraintError(field, value);
  if (constraintError) throw new AssistantCapabilityUsageError(constraintError);
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

function assistantValueHint(field: RecordFormFieldState): string | undefined {
  if (field.valueType === 'LONG' || field.valueType === 'DECIMAL')
    return 'Use a JSON string, not a JSON number, to preserve precision; for example "100.00".';
  if (field.controlType === 'dateInput') return 'Use a valid YYYY-MM-DD date string.';
  if (field.controlType === 'dateTimeInput')
    return 'Use a valid local date-time string, YYYY-MM-DDTHH:mm:ss.';
  return undefined;
}

function invalidFieldValue(field: RecordFormFieldState) {
  return new AssistantCapabilityUsageError(
    `Invalid value for form field: ${field.fieldName}. ${assistantValueHint(field) ?? 'Use the declared field type and allowed option values.'}`,
  );
}

function formFieldStates(view: RecordFormDraftAccess): RecordFormFieldState[] {
  return [...view.formFields.keys()].map((fieldName) => formFieldState(view, fieldName)!);
}

export function formFieldState(
  view: Pick<
    RecordFormDraftAccess,
    'formFields' | 'referencePickerConfigs' | 'editingRecord' | 'selectedRecord' | 'editorMode'
  >,
  fieldName: string,
) {
  if (!view.formFields.has(fieldName)) return undefined;
  return resolveRecordFormFieldState(fieldName, {
    fields: view.formFields,
    pickerConfigs: view.referencePickerConfigs,
    record: view.editingRecord ?? view.selectedRecord,
    mode: view.editorMode,
  });
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function draftSummaryValues(view: RecordFormDraftAccess) {
  const budget = { remaining: MAX_ASSISTANT_FORM_CURRENT_VALUE_CHARS, truncated: false };
  const values = new Map(
    formFieldStates(view)
      .filter((field) => field.visible && !isSensitiveField(field) && field.assistantPolicy !== 'DESCRIBE')
      .map((field) => [field.fieldName, assistantCurrentValue(view, field, budget)]),
  );
  return { values, truncated: budget.truncated };
}
