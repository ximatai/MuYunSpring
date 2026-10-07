import type { MetadataField } from '@muyun/web-contracts';
import type { OperationProposal } from '@muyun/web-core';
import type { MetadataModelChangeSetProposal } from './metadataModelEditSession';
import type { MetadataChangeSetPreview } from './metadataModelChangeSetClient';
import type { MetadataFieldReferencePropertyConfig } from './metadataOrchestrationState';
export interface MetadataGovernanceSummary {
  moduleAlias: string;
  moduleTitle?: string;
  factsAvailable?: boolean;
  submissionStatus?: 'idle' | 'unknown' | 'current-read';
  committedNeedsReload?: boolean;
  relationCount: number;
  selectedRelation?: {
    relationId: string;
    title?: string;
    fieldCount: number;
    fieldsSource?: 'SAVED_CONFIGURATION' | 'UNSAVED_CANDIDATE';
    fields: Array<
      Pick<
        MetadataField,
        'required' | 'uniqueField' | 'indexed' | 'sortableField' | 'titleField' | 'enabled'
      > & {
        fieldName: string;
        columnName?: string;
        title?: string;
        fieldSpecAlias?: string;
        defaultValue?: string | null;
        propertyKind: string;
        governance: string;
        reference?: MetadataFieldReferencePropertyConfig;
      }
    >;
    capabilities?: Array<{
      capability: string;
      title: string;
      enabled: boolean;
      configurable: boolean;
      selected: boolean;
      reason: string;
      fieldContributions: string[];
      defaultDescription: string;
    }>;
    truncated: boolean;
  };
  mainCandidate?: {
    alias: string;
    title: string;
    schemaName: string;
    tableName: string;
    storageDefaultsOnSave: Array<'schemaName' | 'tableName'>;
    saved: false;
    nextStep: 'REVIEW_AND_SAVE_STRUCTURE_BEFORE_FIELDS';
  };
  childCandidate?: {
    alias: string;
    title: string;
    parentRelationId: string;
    parentTitle?: string;
    saved: false;
  };
  draft: {
    active: boolean;
    dirty: boolean;
    editorOpen: boolean;
    fieldPlanOpen?: boolean;
    fieldPlanEditing?: boolean;
  };
  fieldSpecs: Array<{ alias: string; title?: string }>;
}

export interface AddMetadataFieldDraftInput {
  defaultValue?: string | null;
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
  defaultValue?: string | null;
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

export interface UpdateMetadataReferenceDraftInput {
  fieldName: string;
  requireEnabled?: boolean;
  affectMappings?: string[];
}

export type MetadataPropertyFieldKind = 'MODULE_REFERENCE' | 'DICTIONARY';

export interface FindMetadataFieldTargetsInput {
  kind: MetadataPropertyFieldKind;
  keyword?: string;
}

export interface AddMetadataPropertyFieldDraftInput {
  defaultValue?: string | null;
  kind: MetadataPropertyFieldKind;
  title: string;
  fieldName?: string;
  target: string;
  selectionMode?: 'SINGLE' | 'MULTIPLE';
  affectMappings?: string[];
  required?: boolean;
}

export interface PreparedMetadataPropertyFieldDraft {
  defaultValue?: string | null;
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
    affectMappings?: string[];
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
export interface MetadataGovernanceEditor {
  prepareMainDraft?(input: { title: string }): () => unknown;
  prepareChildDraft?(input: { alias: string; title: string }): () => unknown;
  discardCandidate?(): void;
  prepareConfirmation?(signal: AbortSignal): Promise<OperationProposal>;
  readCurrent?(signal?: AbortSignal, commit?: (accept: () => void) => void): Promise<void>;
  prepareFieldPlan?(fields: MetadataFieldPlanInput, signal: AbortSignal): Promise<() => unknown>;
  plan?(): unknown;
  prepareCapabilityDraft?(input: { capability: string; selected: boolean }): () => unknown;
  prepareRetainFieldDraft?(): () => unknown;
  removableNewFieldNames?(): string[];
  prepareRemoveNewFieldDraft?(input: { fieldName: string }): () => unknown;

  summary(): MetadataGovernanceSummary;
  candidate?(): MetadataFieldCandidate | undefined;
  proposal(): MetadataModelChangeSetProposal | undefined;
  preview(proposal: MetadataModelChangeSetProposal, signal: AbortSignal): Promise<MetadataChangeSetPreview>;
  fieldSpecAliases(): string[];
  editableBasicFieldNames(): string[];
  editableReferenceFieldNames?(): string[];
  prepareReferenceUpdate?(
    input: UpdateMetadataReferenceDraftInput,
    signal: AbortSignal,
  ): Promise<() => unknown>;
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
  referenceAffectDirectory?(
    target: string,
    signal: AbortSignal,
  ): Promise<{
    sources: Array<{ fieldName: string; title: string }>;
    destinations: Array<{ fieldName: string; title: string }>;
  }>;
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
