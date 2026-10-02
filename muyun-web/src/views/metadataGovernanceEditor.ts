import type { MetadataField } from '@muyun/web-contracts';
import type { OperationProposal } from '@muyun/web-core';
import type { MetadataModelChangeSetProposal } from './metadataModelEditSession';
import type { MetadataChangeSetPreview } from './metadataModelChangeSetClient';
export interface MetadataGovernanceSummary {
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
  affectMappings?: string[];
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
  prepareFieldPlan?(fields: MetadataFieldPlanInput, signal: AbortSignal): Promise<() => unknown>;
  plan?(): unknown;

  summary(): MetadataGovernanceSummary;
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
