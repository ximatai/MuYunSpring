import type { AssistantSurfaceContext } from '@muyun/web-contracts';
import {
  AssistantCapabilityUsageError,
  type AssistantCapability,
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
    fields: Array<{
      fieldName: string;
      title?: string;
      fieldSpecAlias?: string;
      propertyKind: string;
      governance: string;
    }>;
    truncated: boolean;
  };
  draft: {
    active: boolean;
    dirty: boolean;
    editorOpen: boolean;
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

export interface MetadataGovernanceAssistantAdapter {
  summary(): MetadataGovernanceAssistantModelSummary;
  proposal(): MetadataModelChangeSetProposal | undefined;
  preview(proposal: MetadataModelChangeSetProposal, signal: AbortSignal): Promise<MetadataChangeSetPreview>;
  fieldSpecAliases(): string[];
  editableBasicFieldNames(): string[];
  addFieldDraft?(input: AddMetadataFieldDraftInput): {
    relationId: string;
    fieldName: string;
    columnName: string;
    title: string;
    fieldSpecAlias: string;
  };
  updateFieldDraft?(input: UpdateMetadataFieldDraftInput): {
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
  commitPropertyFieldDraft?(prepared: PreparedMetadataPropertyFieldDraft): {
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
      ...(canAddFieldDraft(adapter) ? [addMetadataFieldDraftCapability(adapter)] : []),
      ...(canUpdateFieldDraft(adapter) ? [updateMetadataFieldDraftCapability(adapter)] : []),
      ...(canAddPropertyFieldDraft(adapter)
        ? [findMetadataFieldTargetsCapability(adapter), addMetadataPropertyFieldDraftCapability(adapter)]
        : []),
      ...(hasChanges(adapter.proposal()) ? [previewMetadataDraftCapability(adapter)] : []),
    ],
    requestTurn,
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
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['kind', 'title', 'target'],
        properties: {
          kind: { type: 'string', enum: ['MODULE_REFERENCE', 'DICTIONARY'] },
          title: { type: 'string', minLength: 1, maxLength: 100 },
          fieldName: {
            type: 'string',
            minLength: 1,
            maxLength: 63,
            pattern: PLATFORM_FIELD_NAME_PATTERN,
          },
          target: { type: 'string', minLength: 1, maxLength: 255 },
          selectionMode: { type: 'string', enum: dictionarySelectionModes },
          required: { type: 'boolean' },
        },
      },
    },
    parseInput: (input) => parseAddPropertyFieldDraftInput(input, dictionarySelectionModes),
    async execute(input, context) {
      if (!adapter.preparePropertyFieldDraft || !adapter.commitPropertyFieldDraft)
        throw new AssistantCapabilityUsageError('Metadata property field drafting is unavailable');
      const prepared = await adapter.preparePropertyFieldDraft(input, context.signal);
      if (!context.isCurrent())
        throw new AssistantCapabilityUsageError('Metadata property field preparation is no longer current');
      return context.applyEffect(() => adapter.commitPropertyFieldDraft!(prepared));
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
        'Update one editable ordinary business field in the selected metadata relation as a visible, unsaved candidate. The user can review, revise or cancel it before using the page save action.',
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
      if (!adapter.updateFieldDraft)
        throw new AssistantCapabilityUsageError('Metadata field updating is unavailable');
      return context.applyEffect(() => adapter.updateFieldDraft!(input));
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
        'Add one ordinary business field to the selected metadata relation as a visible, unsaved candidate. The user can edit or cancel it, and must confirm through the page before it takes effect.',
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
      if (!adapter.addFieldDraft)
        throw new AssistantCapabilityUsageError('Metadata field drafting is unavailable');
      return context.applyEffect(() => adapter.addFieldDraft!(input));
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
        'Describe the current module metadata model, selected relation, visible fields and local draft state. It does not change configuration.',
      inputSchema: emptyAssistantCapabilityInputSchema(),
    },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute() {
      return adapter.summary();
    },
  };
}

function previewMetadataDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<
  Record<string, never>,
  Omit<MetadataChangeSetPreview, 'proposalFingerprint'> & { valid: boolean }
> {
  return {
    effect: 'read',
    present(preview) {
      return {
        title: '配置候选预检（尚未生效）',
        lines: [
          preview.valid ? '预检通过，仍需人工审阅并在页面确认。' : '预检未通过。',
          ...preview.fieldImpacts.map((impact) => impact.description),
          ...preview.errors.map((issue) => issue.message),
          ...preview.warnings.map((issue) => issue.message),
        ]
          .slice(0, 20)
          .map((line) => line.slice(0, 500)),
      };
    },
    descriptor: {
      code: 'configuration.preview-metadata-draft',
      description:
        'Validate and preview the current unsaved metadata candidate through the standard change-set preview contract. It never publishes the candidate.',
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
    adapter.addFieldDraft &&
    summary.selectedRelation &&
    !summary.draft.editorOpen &&
    adapter.fieldSpecAliases().length > 0,
  );
}

function canUpdateFieldDraft(adapter: MetadataGovernanceAssistantAdapter): boolean {
  const summary = adapter.summary();
  return Boolean(
    adapter.updateFieldDraft &&
    summary.selectedRelation &&
    !summary.draft.editorOpen &&
    adapter.editableBasicFieldNames().length > 0,
  );
}

function canAddPropertyFieldDraft(adapter: MetadataGovernanceAssistantAdapter): boolean {
  const summary = adapter.summary();
  return Boolean(
    adapter.findFieldTargets &&
    adapter.preparePropertyFieldDraft &&
    adapter.commitPropertyFieldDraft &&
    summary.selectedRelation &&
    !summary.draft.editorOpen &&
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
