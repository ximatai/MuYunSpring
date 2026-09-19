import type { AssistantSurfaceContext } from '@muyun/web-contracts';
import {
  type AssistantCapability,
  type AssistantSurface,
  type AssistantTurnRequester,
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

export interface MetadataGovernanceAssistantAdapter {
  summary(): MetadataGovernanceAssistantModelSummary;
  proposal(): MetadataModelChangeSetProposal | undefined;
  preview(proposal: MetadataModelChangeSetProposal, signal: AbortSignal): Promise<MetadataChangeSetPreview>;
  fieldSpecAliases(): string[];
  addFieldDraft?(input: AddMetadataFieldDraftInput): {
    relationId: string;
    fieldName: string;
    columnName: string;
    title: string;
    fieldSpecAlias: string;
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
      ...(hasChanges(adapter.proposal()) ? [previewMetadataDraftCapability(adapter)] : []),
    ],
    requestTurn,
  };
}

function addMetadataFieldDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<AddMetadataFieldDraftInput> {
  const aliases = adapter.fieldSpecAliases();
  return {
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
      if (!adapter.addFieldDraft) throw new Error('Metadata field drafting is unavailable');
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
    descriptor: {
      code: 'configuration.describe-metadata-model',
      description:
        'Describe the current module metadata model, selected relation, visible fields and local draft state. It does not change configuration.',
      inputSchema: emptyObjectSchema(),
    },
    parseInput: parseEmptyObject,
    async execute() {
      return adapter.summary();
    },
  };
}

function previewMetadataDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<Record<string, never>> {
  return {
    descriptor: {
      code: 'configuration.preview-metadata-draft',
      description:
        'Validate and preview the current unsaved metadata candidate through the standard change-set preview contract. It never publishes the candidate.',
      inputSchema: emptyObjectSchema(),
    },
    parseInput: parseEmptyObject,
    async execute(_input, context) {
      const proposal = adapter.proposal();
      if (!hasChanges(proposal)) throw new Error('No metadata candidate is available to preview');
      const preview = await adapter.preview(proposal, context.signal);
      if (!context.isCurrent()) throw new Error('Metadata candidate preview is no longer current');
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

function parseAddFieldDraftInput(input: unknown, fieldSpecAliases: string[]): AddMetadataFieldDraftInput {
  if (!isRecord(input)) throw new Error('Capability input must be an object');
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
    throw new Error('Capability input contains unsupported metadata field properties');
  const title = boundedString(input.title, 'title', 100, true);
  const fieldName = boundedString(input.fieldName, 'fieldName', 63, false);
  if (fieldName && !isPlatformFieldName(fieldName))
    throw new Error('fieldName must use lower camel case and start with a lower-case letter');
  if (fieldName && isDynamicRecordReservedFieldName(fieldName))
    throw new Error('fieldName is reserved by the dynamic record protocol');
  const fieldSpecAlias = boundedString(input.fieldSpecAlias, 'fieldSpecAlias', 100, true);
  if (!fieldSpecAliases.includes(fieldSpecAlias)) throw new Error('Unknown metadata field specification');
  return {
    title,
    ...(fieldName ? { fieldName } : {}),
    fieldSpecAlias,
    ...optionalBooleanProperties(input, ['required', 'unique', 'indexed', 'sortable', 'titleField']),
  };
}

function boundedString(value: unknown, name: string, maxLength: number, required: true): string;
function boundedString(value: unknown, name: string, maxLength: number, required: false): string | undefined;
function boundedString(value: unknown, name: string, maxLength: number, required: boolean) {
  if (value === undefined && !required) return undefined;
  if (typeof value !== 'string' || !value.trim() || value.trim().length > maxLength)
    throw new Error(`${name} must be a non-empty string no longer than ${maxLength} characters`);
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
    if (typeof value !== 'boolean') throw new Error(`${name} must be a boolean`);
    result[name] = value;
  }
  return result;
}

function emptyObjectSchema() {
  return { type: 'object', additionalProperties: false, properties: {} };
}

function parseEmptyObject(input: unknown): Record<string, never> {
  if (!isRecord(input) || Object.keys(input).length > 0) throw new Error('Capability input must be empty');
  return {};
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
