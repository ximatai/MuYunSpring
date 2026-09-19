import type { AssistantSurfaceContext } from '@muyun/web-contracts';
import {
  type AssistantCapability,
  type AssistantSurface,
  type AssistantTurnRequester,
} from '@muyun/web-core';
import type { MetadataModelChangeSetProposal } from './metadataModelEditSession';
import type { MetadataChangeSetPreview } from './metadataModelChangeSetClient';

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
  };
}

export interface MetadataGovernanceAssistantAdapter {
  summary(): MetadataGovernanceAssistantModelSummary;
  proposal(): MetadataModelChangeSetProposal | undefined;
  preview(proposal: MetadataModelChangeSetProposal, signal: AbortSignal): Promise<MetadataChangeSetPreview>;
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
      ...(hasChanges(adapter.proposal()) ? [previewMetadataDraftCapability(adapter)] : []),
    ],
    requestTurn,
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
