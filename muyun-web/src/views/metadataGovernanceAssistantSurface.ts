import type { AssistantSurfaceContext, MetadataField } from '@muyun/web-contracts';
import {
  AssistantCapabilityUsageError,
  type AssistantCapability,
  type AssistantOperationProposal,
  type AssistantSurface,
  type AssistantTurnRequester,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
} from '@muyun/web-core';
import type { MetadataModelChangeSetProposal } from './metadataModelEditSession';
import type { MetadataChangeSetPreview } from './metadataModelChangeSetClient';
import {
  isDynamicRecordReservedFieldName,
  isPlatformFieldName,
  PLATFORM_FIELD_NAME_PATTERN,
} from './metadataNaming';

export interface MetadataGovernanceAssistantModelSummary {
  moduleAlias: string;
  moduleTitle?: string;
  relationCount: number;
  selectedRelation?: {
    relationId: string;
    title?: string;
    fieldCount: number;
    fields: Array<
      Pick<
        MetadataField,
        'required' | 'uniqueField' | 'indexed' | 'sortableField' | 'titleField' | 'enabled'
      > & {
        fieldName: string;
        title?: string;
        fieldSpecAlias?: string;
        propertyKind: string;
        governance: string;
      }
    >;
    truncated: boolean;
  };
  mainCandidate?: {
    alias: string;
    title: string;
    schemaName: string;
    tableName: string;
    saved: false;
    nextStep: 'REVIEW_AND_SAVE_STRUCTURE_BEFORE_FIELDS';
  };
  childCandidate?: { alias: string; title: string; parentRelationId: string; saved: false };
  draft: {
    active: boolean;
    dirty: boolean;
    editorOpen: boolean;
    fieldPlanOpen?: boolean;
  };
  fieldSpecs: Array<{ alias: string; title?: string }>;
}

export interface AddMetadataFieldDraftInput {
  title: string;
  fieldName?: string;
  fieldSpecAlias: string;
  required?: boolean;
  unique?: boolean;
  indexed?: boolean;
  sortable?: boolean;
  titleField?: boolean;
}

export interface UpdateMetadataFieldDraftInput {
  fieldName: string;
  title?: string;
  fieldSpecAlias?: string;
  required?: boolean;
  unique?: boolean;
  indexed?: boolean;
  sortable?: boolean;
  titleField?: boolean;
  enabled?: boolean;
}

export type MetadataPropertyFieldKind = 'MODULE_REFERENCE' | 'DICTIONARY';

export interface FindMetadataFieldTargetsInput {
  kind: MetadataPropertyFieldKind;
  keyword?: string;
}

export interface AddMetadataPropertyFieldDraftInput {
  kind: MetadataPropertyFieldKind;
  title: string;
  fieldName?: string;
  target: string;
  selectionMode?: 'SINGLE' | 'MULTIPLE';
  required?: boolean;
}

export interface PreparedMetadataPropertyFieldDraft {
  relationId: string;
  kind: MetadataPropertyFieldKind;
  title: string;
  fieldName: string;
  columnName: string;
  fieldSpecAlias: string;
  required: boolean;
  reference?: {
    targetModuleAlias: string;
    targetMetadataId?: string;
    targetKeyField: string;
    targetLabelField: string;
  };
  dictionary?: {
    applicationAlias: string;
    categoryAlias: string;
    selectionMode: 'SINGLE' | 'MULTIPLE';
  };
}

export interface MetadataFieldCandidate {
  fieldName: string;
  kind: string;
  editable: boolean;
  operation: 'ADD' | 'UPDATE';
  saved: false;
  expectedMetadataVersion: number;
  changes: Array<{ property: string; before?: string | boolean; after?: string | boolean }>;
}

export type MetadataFieldPlanInput = Array<
  (AddMetadataFieldDraftInput & { kind: 'BASIC' }) | AddMetadataPropertyFieldDraftInput
>;

/** Preparation validates without changing the editor; returned callbacks commit synchronously inside applyEffect. */
export interface MetadataGovernanceAssistantAdapter {
  prepareMainDraft?(input: { title: string }): () => unknown;
  prepareChildDraft?(input: { alias: string; title: string }): () => unknown;
  discardCandidate?(): void;
  prepareConfirmation?(signal: AbortSignal): Promise<AssistantOperationProposal>;
  prepareFieldPlan?(fields: MetadataFieldPlanInput, signal: AbortSignal): Promise<() => unknown>;
  plan?(): unknown;

  summary(): MetadataGovernanceAssistantModelSummary;
  candidate?(): MetadataFieldCandidate | undefined;
  proposal(): MetadataModelChangeSetProposal | undefined;
  preview(proposal: MetadataModelChangeSetProposal, signal: AbortSignal): Promise<MetadataChangeSetPreview>;
  fieldSpecAliases(): string[];
  editableBasicFieldNames(): string[];
  prepareNewFieldDraft?(input: AddMetadataFieldDraftInput): () => {
    relationId: string;
    fieldName: string;
    columnName: string;
    title: string;
    fieldSpecAlias: string;
  };
  prepareFieldUpdate?(input: UpdateMetadataFieldDraftInput): () => {
    relationId: string;
    fieldName: string;
    title?: string;
    fieldSpecAlias?: string;
  };
  findFieldTargets?(
    input: FindMetadataFieldTargetsInput,
    signal: AbortSignal,
  ): Promise<{ targets: Array<{ target: string; title?: string }>; truncated: boolean }>;
  preparePropertyFieldDraft?(
    input: AddMetadataPropertyFieldDraftInput,
    signal: AbortSignal,
  ): Promise<PreparedMetadataPropertyFieldDraft>;
  preparePropertyFieldCommit?(prepared: PreparedMetadataPropertyFieldDraft): () => {
    relationId: string;
    kind: MetadataPropertyFieldKind;
    fieldName: string;
    columnName: string;
    title: string;
    fieldSpecAlias: string;
    target: string;
  };
}

export function createMetadataGovernanceAssistantSurface(
  adapter: MetadataGovernanceAssistantAdapter,
  requestTurn: AssistantTurnRequester,
  contributedCapabilities: () => AssistantCapability[] = () => [],
): AssistantSurface {
  return {
    describe: () => surfaceContext(adapter.summary()),
    capabilities: () => [
      ...contributedCapabilities(),
      describeMetadataModelCapability(adapter),
      ...(adapter.prepareMainDraft &&
      adapter.summary().relationCount === 0 &&
      (!adapter.summary().draft.active || adapter.summary().mainCandidate)
        ? [prepareMetadataMainDraftCapability(adapter)]
        : []),
      ...(adapter.prepareChildDraft &&
      adapter.summary().selectedRelation &&
      (!adapter.summary().draft.active || adapter.summary().childCandidate)
        ? [prepareMetadataChildDraftCapability(adapter)]
        : []),
      ...(adapter.discardCandidate && adapter.summary().draft.active
        ? [
            {
              effect: 'configuration-draft' as const,
              descriptor: {
                code: 'configuration.discard-metadata-draft',
                description:
                  'Discard the entire current unsaved metadata candidate, including manual edits, only when the user asks to abandon it. Does not delete persisted fields or undo applied configuration.',
                inputSchema: emptyAssistantCapabilityInputSchema(),
              },
              parseInput: parseEmptyAssistantCapabilityInput,
              async execute(_input: unknown, context: Parameters<AssistantCapability['execute']>[1]) {
                return context.applyEffect(() => {
                  adapter.discardCandidate!();
                  return { discarded: true, saved: false };
                });
              },
            },
          ]
        : []),
      ...(adapter.prepareFieldPlan && canAddFieldDraft(adapter) && !adapter.summary().draft.active
        ? [prepareMetadataFieldPlanCapability(adapter)]
        : []),
      ...(adapter.candidate?.() ? [describeMetadataCandidateCapability(adapter)] : []),
      ...(canAddFieldDraft(adapter) ? [addMetadataFieldDraftCapability(adapter)] : []),
      ...(canUpdateFieldDraft(adapter) ? [updateMetadataFieldDraftCapability(adapter)] : []),
      ...(canAddPropertyFieldDraft(adapter)
        ? [findMetadataFieldTargetsCapability(adapter), addMetadataPropertyFieldDraftCapability(adapter)]
        : []),
      ...(hasChanges(adapter.proposal()) ? [previewMetadataDraftCapability(adapter)] : []),
      ...(adapter.prepareConfirmation && (hasChanges(adapter.proposal()) || adapter.summary().childCandidate)
        ? [prepareMetadataConfirmationCapability(adapter)]
        : []),
    ],
    requestTurn,
  };
}

function prepareMetadataMainDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability {
  return {
    effect: 'configuration-draft',
    descriptor: {
      code: 'configuration.prepare-metadata-main-draft',
      description:
        'Prepare the missing main entity in the standard metadata editor. Available only when the module has no metadata relations. The standard editor derives its identifier from the module; provide the business title. This only prepares a visible draft, never creates storage. Open the metadata editor and ask the user to review and save there; then reread metadata before adding fields. Preserve existing manual storage settings.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['title'],
        properties: { title: { type: 'string', minLength: 1, maxLength: 120 } },
      },
    },
    parseInput(input) {
      if (!isRecord(input) || Object.keys(input).some((key) => key !== 'title'))
        throw new AssistantCapabilityUsageError('请提供主实体名称');
      return { title: boundedString(input.title, 'title', 120, true) };
    },
    async execute(input, context) {
      return context.applyEffect(adapter.prepareMainDraft!(input as { title: string }));
    },
  };
}

function prepareMetadataChildDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability {
  return {
    effect: 'configuration-draft',
    descriptor: {
      code: 'configuration.prepare-metadata-child-draft',
      description:
        'Prepare or revise one child table under the selected metadata relation in the shared editor. Use for repeatable line items belonging to a parent record, not independently managed objects. Does not create storage. Read the candidate with describe-metadata-model, then use prepare-metadata-apply for human confirmation. After creation configure its fields, references, formulas and page through standard governance; an empty child is not business completion.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['alias', 'title'],
        properties: {
          alias: { type: 'string', pattern: '^[a-z][a-z0-9_]{0,62}$', maxLength: 63 },
          title: { type: 'string', minLength: 1, maxLength: 120 },
        },
      },
    },
    parseInput(input) {
      if (!isRecord(input) || Object.keys(input).some((key) => !['alias', 'title'].includes(key)))
        throw new AssistantCapabilityUsageError('请提供明细名称和标识');
      const alias = boundedString(input.alias, 'alias', 63, true);
      if (!/^[a-z][a-z0-9_]{0,62}$/.test(alias)) throw new AssistantCapabilityUsageError('明细标识格式无效');
      return { alias, title: boundedString(input.title, 'title', 120, true) };
    },
    async execute(input, context) {
      return context.applyEffect(adapter.prepareChildDraft!(input as { alias: string; title: string }));
    },
  };
}

function prepareMetadataConfirmationCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability {
  let prepared: AssistantOperationProposal | undefined;
  return {
    effect: 'read',
    descriptor: {
      code: 'configuration.prepare-metadata-apply',
      description:
        'Prepare human confirmation for the entire current metadata candidate, including manual edits, through standard metadata governance. A child-table candidate creates only its empty storage and parent relationship; field candidates use the change-set precheck. Only a human click saves it. Do not ask the user to leave the conversation to press page save. Configuration persistence does not imply runtime activation.',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute(_input, context) {
      const proposal = await adapter.prepareConfirmation!(context.signal);
      context.commitInternalState(() => {
        prepared = proposal;
      });
      return { pendingConfirmation: true, saved: false };
    },
    propose() {
      if (!prepared) throw new AssistantCapabilityUsageError('请重新准备配置确认');
      return prepared;
    },
  };
}

function prepareMetadataFieldPlanCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<MetadataFieldPlanInput> {
  const basicSchema = addMetadataFieldDraftCapability(adapter).descriptor.inputSchema;
  const propertySchemas = metadataPropertyFieldSchemas(adapter);
  return {
    effect: 'configuration-draft',
    descriptor: {
      code: 'configuration.prepare-metadata-field-plan',
      description:
        'Prepare 1–12 new fields together as one visible unsaved plan. Supports BASIC, MODULE_REFERENCE and DICTIONARY fields. Resolve targets first. All fields must validate before any candidate is staged. The user can edit/remove each item and must confirm the standard change-set before anything is saved.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['fields'],
        properties: {
          fields: {
            type: 'array',
            minItems: 1,
            maxItems: 12,
            items: {
              anyOf: [
                {
                  ...basicSchema,
                  required: ['kind', 'title', 'fieldSpecAlias'],
                  properties: {
                    ...(isRecord(basicSchema.properties) ? basicSchema.properties : {}),
                    kind: { type: 'string', enum: ['BASIC'] },
                  },
                },
                ...propertySchemas,
              ],
            },
          },
        },
      },
    },
    parseInput(input) {
      if (
        !isRecord(input) ||
        Object.keys(input).some((key) => key !== 'fields') ||
        !Array.isArray(input.fields) ||
        input.fields.length < 1 ||
        input.fields.length > 12
      )
        throw new AssistantCapabilityUsageError('A field plan requires 1–12 fields');
      return input.fields.map((item) => {
        if (!isRecord(item)) throw new AssistantCapabilityUsageError('Invalid field plan item');
        if (item.kind === 'BASIC') {
          const { kind, ...field } = item;
          return { ...parseAddFieldDraftInput(field, adapter.fieldSpecAliases()), kind };
        }
        return parseAddPropertyFieldDraftInput(
          item,
          adapter.fieldSpecAliases().includes('json_set') ? ['SINGLE', 'MULTIPLE'] : ['SINGLE'],
        );
      });
    },
    async execute(input, context) {
      const commit = await adapter.prepareFieldPlan!(input, context.signal);
      if (!context.isCurrent())
        throw new AssistantCapabilityUsageError('Field plan preparation is no longer current');
      return context.applyEffect(commit);
    },
  };
}

function findMetadataFieldTargetsCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<FindMetadataFieldTargetsInput> {
  return {
    effect: 'read',
    descriptor: {
      code: 'configuration.find-metadata-field-targets',
      description:
        'Find valid target module aliases or dictionary identities before adding a reference or dictionary metadata field. Use a returned target exactly; do not guess internal identities.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['kind'],
        properties: {
          kind: { type: 'string', enum: ['MODULE_REFERENCE', 'DICTIONARY'] },
          keyword: { type: 'string', minLength: 1, maxLength: 100 },
        },
      },
    },
    parseInput: parseFindFieldTargetsInput,
    async execute(input, context) {
      if (!adapter.findFieldTargets)
        throw new AssistantCapabilityUsageError('Metadata field target lookup is unavailable');
      const result = await adapter.findFieldTargets(input, context.signal);
      if (!context.isCurrent())
        throw new AssistantCapabilityUsageError('Metadata field target lookup is no longer current');
      return { kind: input.kind, ...result };
    },
  };
}

/** Disjoint variants expose the same field-kind boundary enforced by the parser. */
function metadataPropertyFieldSchemas(adapter: MetadataGovernanceAssistantAdapter) {
  return (['MODULE_REFERENCE', 'DICTIONARY'] as const).map((kind) => ({
    type: 'object',
    additionalProperties: false,
    required: ['kind', 'title', 'target'],
    properties: {
      kind: { type: 'string', enum: [kind] },
      title: { type: 'string', minLength: 1, maxLength: 100 },
      fieldName: {
        type: 'string',
        minLength: 1,
        maxLength: 63,
        pattern: PLATFORM_FIELD_NAME_PATTERN,
      },
      target: { type: 'string', minLength: 1, maxLength: 255 },
      ...(kind === 'DICTIONARY'
        ? {
            selectionMode: {
              type: 'string',
              enum: adapter.fieldSpecAliases().includes('json_set') ? ['SINGLE', 'MULTIPLE'] : ['SINGLE'],
            },
          }
        : {}),
      required: { type: 'boolean' },
    },
  }));
}

function addMetadataPropertyFieldDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<AddMetadataPropertyFieldDraftInput> {
  const dictionarySelectionModes: Array<'SINGLE' | 'MULTIPLE'> = adapter
    .fieldSpecAliases()
    .includes('json_set')
    ? ['SINGLE', 'MULTIPLE']
    : ['SINGLE'];
  return {
    effect: 'configuration-draft',
    descriptor: {
      code: 'configuration.add-metadata-property-field-draft',
      description:
        'Add a module-reference or dictionary field as a visible, unsaved metadata candidate. First resolve target with configuration.find-metadata-field-targets, then pass the exact returned target.',
      inputSchema: { type: 'object', anyOf: metadataPropertyFieldSchemas(adapter) },
    },
    parseInput: (input) => parseAddPropertyFieldDraftInput(input, dictionarySelectionModes),
    async execute(input, context) {
      if (!adapter.preparePropertyFieldDraft || !adapter.preparePropertyFieldCommit)
        throw new AssistantCapabilityUsageError('Metadata property field drafting is unavailable');
      const prepared = await adapter.preparePropertyFieldDraft(input, context.signal);
      if (!context.isCurrent())
        throw new AssistantCapabilityUsageError('Metadata property field preparation is no longer current');
      const commit = adapter.preparePropertyFieldCommit(prepared);
      return context.applyEffect(commit);
    },
  };
}

function updateMetadataFieldDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<UpdateMetadataFieldDraftInput> {
  const fieldNames = adapter.editableBasicFieldNames();
  const fieldSpecAliases = adapter.fieldSpecAliases();
  return {
    effect: 'configuration-draft',
    descriptor: {
      code: 'configuration.update-metadata-field-draft',
      description:
        'Update one editable ordinary business field as a visible, unsaved candidate, including one field in an unsaved batch plan while preserving its other fields. When an individual field editor is open, revise only that current candidate; first describe it to inspect the user’s latest changes. The user can review, revise or cancel it before confirming the standard change-set.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['fieldName'],
        properties: {
          fieldName: { type: 'string', enum: fieldNames },
          title: { type: 'string', minLength: 1, maxLength: 100 },
          fieldSpecAlias: { type: 'string', enum: fieldSpecAliases },
          required: { type: 'boolean' },
          unique: { type: 'boolean' },
          indexed: { type: 'boolean' },
          sortable: { type: 'boolean' },
          titleField: { type: 'boolean' },
          enabled: { type: 'boolean' },
        },
      },
    },
    parseInput: (input) => parseUpdateFieldDraftInput(input, fieldNames, fieldSpecAliases),
    async execute(input, context) {
      if (!adapter.prepareFieldUpdate)
        throw new AssistantCapabilityUsageError('Metadata field updating is unavailable');
      const commit = adapter.prepareFieldUpdate(input);
      return context.applyEffect(commit);
    },
  };
}

function addMetadataFieldDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<AddMetadataFieldDraftInput> {
  const aliases = adapter.fieldSpecAliases();
  return {
    effect: 'configuration-draft',
    descriptor: {
      code: 'configuration.add-metadata-field-draft',
      description:
        'Add one ordinary business field, including an addition to the current batch, as a visible unsaved candidate; preserve other fields. Stored result columns are ordinary writable metadata. Calculation rules provide their read-only form projection; apply those rules before publishing the form. The user must confirm the standard change-set to save.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['title', 'fieldSpecAlias'],
        properties: {
          title: { type: 'string', minLength: 1, maxLength: 100 },
          fieldName: {
            type: 'string',
            minLength: 1,
            maxLength: 63,
            pattern: PLATFORM_FIELD_NAME_PATTERN,
          },
          fieldSpecAlias: { type: 'string', enum: aliases },
          required: { type: 'boolean' },
          unique: { type: 'boolean' },
          indexed: { type: 'boolean' },
          sortable: { type: 'boolean' },
          titleField: { type: 'boolean' },
        },
      },
    },
    parseInput: (input) => parseAddFieldDraftInput(input, aliases),
    async execute(input, context) {
      if (!adapter.prepareNewFieldDraft)
        throw new AssistantCapabilityUsageError('Metadata field drafting is unavailable');
      const commit = adapter.prepareNewFieldDraft(input);
      return context.applyEffect(commit);
    },
  };
}

function surfaceContext(summary: MetadataGovernanceAssistantModelSummary): AssistantSurfaceContext {
  return {
    surface: 'metadata-governance',
    title: summary.moduleTitle ? `${summary.moduleTitle} · 元数据` : `${summary.moduleAlias} · 元数据`,
    facts: {
      moduleAlias: summary.moduleAlias,
      relationCount: summary.relationCount,
      mainCandidate: summary.mainCandidate,
      selectedRelationId: summary.selectedRelation?.relationId,
      editing: summary.draft.active,
      dirty: summary.draft.dirty,
    },
  };
}

function describeMetadataModelCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<Record<string, never>> {
  return {
    effect: 'read',
    descriptor: {
      code: 'configuration.describe-metadata-model',
      description:
        'Describe the current module metadata model, selected relation, visible fields with their current required, unique, index, sorting, title and enabled settings, and local draft state. Includes staged fields in a batch plan. Reuse these facts to compare the requested business constraints; settings already satisfied need no update. It does not change configuration.',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute() {
      return adapter.summary();
    },
  };
}

function describeMetadataCandidateCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<Record<string, never>, MetadataFieldCandidate> {
  return {
    effect: 'read',
    descriptor: {
      code: 'configuration.describe-metadata-candidate',
      description:
        'Read the visible unsaved field candidate and its differences from the persisted baseline, including manual edits. Use before revising a candidate. Preview already includes these differences: do not read again merely to summarize that preview. It does not save or publish.',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute() {
      const candidate = adapter.candidate?.();
      if (!candidate)
        throw new AssistantCapabilityUsageError(
          'No visible metadata candidate is available',
          'PRECONDITION_FAILED',
        );
      return candidate;
    },
    present(candidate) {
      const display = (value: string | boolean | undefined) =>
        value === undefined
          ? '未设置'
          : typeof value === 'boolean'
            ? value
              ? '是'
              : '否'
            : value.slice(0, 200);
      const summary = adapter.summary();
      const name =
        candidate.changes.find((change) => change.property === '显示名称')?.after ||
        summary.selectedRelation?.fields.find((field) => field.fieldName === candidate.fieldName)?.title ||
        candidate.fieldName;
      const changes = candidate.changes.map(
        (change) => `${change.property}：${display(change.before)} → ${display(change.after)}`,
      );
      const businessChanges = candidate.changes.filter((change) =>
        ['显示名称', '必填', '唯一', '启用'].includes(change.property),
      );
      return {
        title: `「${name}」的修改（尚未保存）`,
        lines: businessChanges.length
          ? businessChanges.map(
              (change) => `${change.property}：${display(change.before)} → ${display(change.after)}`,
            )
          : [candidate.changes.length ? '已准备配置更改，可查看详细内容。' : '当前内容与已保存配置一致。'],
        ...(changes.length ? { details: { title: '查看详细配置', lines: changes } } : {}),
      };
    },
  };
}

function previewMetadataDraftCapability(adapter: MetadataGovernanceAssistantAdapter): AssistantCapability<
  Record<string, never>,
  Omit<MetadataChangeSetPreview, 'proposalFingerprint'> & {
    valid: boolean;
    candidate?: MetadataFieldCandidate;
    plan?: unknown;
  }
> {
  return {
    effect: 'read',
    present(preview) {
      const details = [
        ...preview.errors.map((issue) => ({ kind: '错误', text: issue.message })),
        ...preview.warnings.map((issue) => ({ kind: '警告', text: issue.message })),
        ...preview.fieldImpacts.map((impact) => ({ kind: '字段影响', text: impact.description })),
      ];
      // Reserve one line for the conclusion and, when needed, one for omitted counts.
      const visibleCount = details.length > 19 ? 18 : 19;
      const omitted = details.slice(visibleCount);
      const omittedSummary = ['错误', '警告', '字段影响']
        .map((kind) => ({ kind, count: omitted.filter((item) => item.kind === kind).length }))
        .filter(({ count }) => count > 0)
        .map(({ kind, count }) => `${count} 项${kind}`)
        .join('、');
      return {
        title: '配置候选预检（尚未生效）',
        lines: [
          preview.valid ? '预检通过，仍需人工审阅并确认保存。' : '预检未通过。',
          ...details.slice(0, visibleCount).map(({ text }) => text.slice(0, 500)),
          ...(omitted.length ? [`另有 ${omittedSummary}未展示，请在配置页面查看完整预检结果。`] : []),
        ],
      };
    },
    descriptor: {
      code: 'configuration.preview-metadata-draft',
      description:
        'Validate the current visible unsaved candidate through the standard change-set preview contract. Returns both candidate differences and authoritative validation/impacts, sufficient to report the result. After this returns, summarize and stop tool calls; preview again only after the candidate changes. It never publishes the candidate.',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute(_input, context) {
      const proposal = adapter.proposal();
      if (!hasChanges(proposal))
        throw new AssistantCapabilityUsageError('No metadata candidate is available to preview');
      const preview = await adapter.preview(proposal, context.signal);
      if (!context.isCurrent())
        throw new AssistantCapabilityUsageError('Metadata candidate preview is no longer current');
      return {
        valid: preview.errors.length === 0,
        ...(adapter.plan?.() ? { plan: adapter.plan() } : {}),
        ...(adapter.candidate?.() ? { candidate: adapter.candidate() } : {}),
        fieldImpacts: preview.fieldImpacts,
        schemaImpacts: preview.schemaImpacts,
        orderImpacts: preview.orderImpacts,
        warnings: preview.warnings,
        errors: preview.errors,
      };
    },
  };
}

function hasChanges(
  proposal: MetadataModelChangeSetProposal | undefined,
): proposal is MetadataModelChangeSetProposal {
  return Boolean(
    proposal &&
    (proposal.relationDrafts.length > 0 ||
      proposal.relationOrders.length > 0 ||
      proposal.fieldOrders.length > 0),
  );
}

function canAddFieldDraft(adapter: MetadataGovernanceAssistantAdapter): boolean {
  const summary = adapter.summary();
  return Boolean(
    adapter.prepareNewFieldDraft &&
    summary.selectedRelation &&
    (!summary.draft.editorOpen || summary.draft.fieldPlanOpen) &&
    adapter.fieldSpecAliases().length > 0,
  );
}

function canUpdateFieldDraft(adapter: MetadataGovernanceAssistantAdapter): boolean {
  const summary = adapter.summary();
  return Boolean(
    adapter.prepareFieldUpdate &&
    summary.selectedRelation &&
    (!summary.draft.editorOpen || summary.draft.fieldPlanOpen || adapter.candidate?.()?.editable === true) &&
    adapter.editableBasicFieldNames().length > 0,
  );
}

function canAddPropertyFieldDraft(adapter: MetadataGovernanceAssistantAdapter): boolean {
  const summary = adapter.summary();
  return Boolean(
    adapter.findFieldTargets &&
    adapter.preparePropertyFieldDraft &&
    adapter.preparePropertyFieldCommit &&
    summary.selectedRelation &&
    (!summary.draft.editorOpen || summary.draft.fieldPlanOpen) &&
    adapter.fieldSpecAliases().includes('string'),
  );
}

function parseAddFieldDraftInput(input: unknown, fieldSpecAliases: string[]): AddMetadataFieldDraftInput {
  if (!isRecord(input)) throw new AssistantCapabilityUsageError('Capability input must be an object');
  const allowed = new Set([
    'title',
    'fieldName',
    'fieldSpecAlias',
    'required',
    'unique',
    'indexed',
    'sortable',
    'titleField',
  ]);
  if (Object.keys(input).some((key) => !allowed.has(key)))
    throw new AssistantCapabilityUsageError(
      'Capability input contains unsupported metadata field properties',
    );
  const title = boundedString(input.title, 'title', 100, true);
  const fieldName = boundedString(input.fieldName, 'fieldName', 63, false);
  if (fieldName && !isPlatformFieldName(fieldName))
    throw new AssistantCapabilityUsageError(
      'fieldName must use lower camel case and start with a lower-case letter',
    );
  if (fieldName && isDynamicRecordReservedFieldName(fieldName))
    throw new AssistantCapabilityUsageError('fieldName is reserved by the dynamic record protocol');
  const fieldSpecAlias = boundedString(input.fieldSpecAlias, 'fieldSpecAlias', 100, true);
  if (!fieldSpecAliases.includes(fieldSpecAlias))
    throw new AssistantCapabilityUsageError('Unknown metadata field specification');
  return {
    title,
    ...(fieldName ? { fieldName } : {}),
    fieldSpecAlias,
    ...optionalBooleanProperties(input, ['required', 'unique', 'indexed', 'sortable', 'titleField']),
  };
}

function parseUpdateFieldDraftInput(
  input: unknown,
  fieldNames: string[],
  fieldSpecAliases: string[],
): UpdateMetadataFieldDraftInput {
  if (!isRecord(input)) throw new AssistantCapabilityUsageError('Capability input must be an object');
  const allowed = new Set([
    'fieldName',
    'title',
    'fieldSpecAlias',
    'required',
    'unique',
    'indexed',
    'sortable',
    'titleField',
    'enabled',
  ]);
  if (Object.keys(input).some((key) => !allowed.has(key)))
    throw new AssistantCapabilityUsageError(
      'Capability input contains unsupported metadata field properties',
    );
  const fieldName = boundedString(input.fieldName, 'fieldName', 63, true);
  if (!fieldNames.includes(fieldName))
    throw new AssistantCapabilityUsageError('Metadata field is unavailable for editing');
  const title = boundedString(input.title, 'title', 100, false);
  const fieldSpecAlias = boundedString(input.fieldSpecAlias, 'fieldSpecAlias', 100, false);
  if (fieldSpecAlias && !fieldSpecAliases.includes(fieldSpecAlias))
    throw new AssistantCapabilityUsageError('Unknown metadata field specification');
  const changes = {
    ...(title ? { title } : {}),
    ...(fieldSpecAlias ? { fieldSpecAlias } : {}),
    ...optionalBooleanProperties(input, [
      'required',
      'unique',
      'indexed',
      'sortable',
      'titleField',
      'enabled',
    ]),
  };
  if (Object.keys(changes).length === 0)
    throw new AssistantCapabilityUsageError('At least one metadata field change is required');
  return { fieldName, ...changes };
}

function parseFindFieldTargetsInput(input: unknown): FindMetadataFieldTargetsInput {
  if (!isRecord(input)) throw new AssistantCapabilityUsageError('Capability input must be an object');
  if (Object.keys(input).some((key) => !['kind', 'keyword'].includes(key)))
    throw new AssistantCapabilityUsageError('Capability input contains unsupported target lookup properties');
  const kind = metadataPropertyFieldKind(input.kind);
  const keyword = boundedString(input.keyword, 'keyword', 100, false);
  return { kind, ...(keyword ? { keyword } : {}) };
}

function parseAddPropertyFieldDraftInput(
  input: unknown,
  dictionarySelectionModes: Array<'SINGLE' | 'MULTIPLE'>,
): AddMetadataPropertyFieldDraftInput {
  if (!isRecord(input)) throw new AssistantCapabilityUsageError('Capability input must be an object');
  const allowed = new Set(['kind', 'title', 'fieldName', 'target', 'selectionMode', 'required']);
  if (Object.keys(input).some((key) => !allowed.has(key)))
    throw new AssistantCapabilityUsageError(
      'Capability input contains unsupported metadata property field properties',
    );
  const kind = metadataPropertyFieldKind(input.kind);
  const title = boundedString(input.title, 'title', 100, true);
  const fieldName = boundedString(input.fieldName, 'fieldName', 63, false);
  if (fieldName && !isPlatformFieldName(fieldName))
    throw new AssistantCapabilityUsageError(
      'fieldName must use lower camel case and start with a lower-case letter',
    );
  if (fieldName && isDynamicRecordReservedFieldName(fieldName))
    throw new AssistantCapabilityUsageError('fieldName is reserved by the dynamic record protocol');
  const target = boundedString(input.target, 'target', 255, true);
  const selectionMode = input.selectionMode;
  if (selectionMode !== undefined && selectionMode !== 'SINGLE' && selectionMode !== 'MULTIPLE')
    throw new AssistantCapabilityUsageError('selectionMode must be SINGLE or MULTIPLE');
  if (kind === 'MODULE_REFERENCE' && selectionMode !== undefined)
    throw new AssistantCapabilityUsageError('selectionMode is only supported for dictionary fields');
  if (kind === 'DICTIONARY' && selectionMode && !dictionarySelectionModes.includes(selectionMode))
    throw new AssistantCapabilityUsageError(
      'selectionMode is unavailable because its storage field specification is disabled',
    );
  const required = optionalBooleanProperties(input, ['required']).required;
  return {
    kind,
    title,
    ...(fieldName ? { fieldName } : {}),
    target,
    ...(kind === 'DICTIONARY' ? { selectionMode: selectionMode ?? 'SINGLE' } : {}),
    ...(required !== undefined ? { required } : {}),
  };
}

function metadataPropertyFieldKind(value: unknown): MetadataPropertyFieldKind {
  if (value !== 'MODULE_REFERENCE' && value !== 'DICTIONARY')
    throw new AssistantCapabilityUsageError('kind must be MODULE_REFERENCE or DICTIONARY');
  return value;
}

function boundedString(value: unknown, name: string, maxLength: number, required: true): string;
function boundedString(value: unknown, name: string, maxLength: number, required: false): string | undefined;
function boundedString(value: unknown, name: string, maxLength: number, required: boolean) {
  if (value === undefined && !required) return undefined;
  if (typeof value !== 'string' || !value.trim() || value.trim().length > maxLength)
    throw new AssistantCapabilityUsageError(
      `${name} must be a non-empty string no longer than ${maxLength} characters`,
    );
  return value.trim();
}

function optionalBooleanProperties<T extends string>(
  input: Record<string, unknown>,
  names: T[],
): Partial<Record<T, boolean>> {
  const result: Partial<Record<T, boolean>> = {};
  for (const name of names) {
    const value = input[name];
    if (value === undefined) continue;
    if (typeof value !== 'boolean') throw new AssistantCapabilityUsageError(`${name} must be a boolean`);
    result[name] = value;
  }
  return result;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
