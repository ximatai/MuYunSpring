import type { AssistantSurfaceContext } from '@muyun/web-contracts';
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

import type {
  MetadataGovernanceSummary as MetadataGovernanceAssistantModelSummary,
  AddMetadataFieldDraftInput,
  UpdateMetadataFieldDraftInput,
  MetadataPropertyFieldKind,
  FindMetadataFieldTargetsInput,
  UpdateMetadataReferenceDraftInput,
  AddMetadataPropertyFieldDraftInput,
  MetadataFieldCandidate,
  MetadataFieldPlanInput,
  MetadataGovernanceEditor as MetadataGovernanceAssistantAdapter,
} from './metadataGovernanceEditor';
export type {
  MetadataGovernanceSummary as MetadataGovernanceAssistantModelSummary,
  AddMetadataFieldDraftInput,
  UpdateMetadataFieldDraftInput,
  MetadataPropertyFieldKind,
  FindMetadataFieldTargetsInput,
  AddMetadataPropertyFieldDraftInput,
  PreparedMetadataPropertyFieldDraft,
  MetadataFieldCandidate,
  MetadataFieldPlanInput,
  MetadataGovernanceEditor as MetadataGovernanceAssistantAdapter,
} from './metadataGovernanceEditor';
export function createMetadataGovernanceAssistantSurface(
  adapter: MetadataGovernanceAssistantAdapter,
  requestTurn: AssistantTurnRequester,
  contributedCapabilities: () => AssistantCapability[] = () => [],
): AssistantSurface {
  return {
    describe: () => surfaceContext(adapter.summary()),
    capabilities: () =>
      adapter.summary().factsAvailable === false
        ? [
            ...contributedCapabilities(),
            ...(adapter.readCurrent &&
            (adapter.summary().submissionStatus === 'unknown' || adapter.summary().committedNeedsReload)
              ? [
                  {
                    effect: 'read' as const,
                    changesReadState: true,
                    descriptor: {
                      code: 'configuration.read-current-metadata',
                      description:
                        'Read current standard metadata after an unknown submission or a confirmed save whose synchronization failed. Preserve candidate intent on the new baseline for fresh human review. Never resubmit or infer a receipt for an unknown original request.',
                      inputSchema: emptyAssistantCapabilityInputSchema(),
                    },
                    parseInput: parseEmptyAssistantCapabilityInput,
                    async execute(_input: unknown, context: Parameters<AssistantCapability['execute']>[1]) {
                      const unknown = adapter.summary().submissionStatus === 'unknown';
                      await adapter.readCurrent!(context.signal, context.commitInternalState);
                      return {
                        originalSubmission: unknown ? 'unknown' : 'committed',
                        currentConfigurationRead: true,
                        candidatePreserved: true,
                      };
                    },
                    present: () => ({
                      title: '已读取当前元数据',
                      lines: ['已在当前基线上保留候选意图；原未知提交不据此认定成功，后续保存须重新审阅。'],
                    }),
                  } satisfies AssistantCapability,
                ]
              : []),
          ]
        : [
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
            ...(adapter.prepareCapabilityDraft &&
            adapter
              .summary()
              .selectedRelation?.capabilities?.some((fact) => fact.configurable && !fact.enabled) &&
            (!adapter.summary().draft.editorOpen || adapter.summary().draft.fieldPlanOpen)
              ? [prepareMetadataCapabilityDraftCapability(adapter)]
              : []),
            ...(adapter.candidate?.() ? [describeMetadataCandidateCapability(adapter)] : []),
            ...(adapter.prepareRetainFieldDraft &&
            adapter.summary().draft.fieldPlanEditing &&
            adapter.candidate?.()
              ? [
                  {
                    effect: 'configuration-draft' as const,
                    descriptor: {
                      code: 'configuration.retain-metadata-field-draft',
                      description:
                        'Retain the current field editor, including the user’s latest changes, in the shared unsaved batch and return to its field list. Does not save or publish. Retain before adding another field, removing a new field, or selecting a base capability.',
                      inputSchema: emptyAssistantCapabilityInputSchema(),
                    },
                    parseInput: parseEmptyAssistantCapabilityInput,
                    async execute(_input: unknown, context: Parameters<AssistantCapability['execute']>[1]) {
                      const commit = adapter.prepareRetainFieldDraft!();
                      return context.applyEffect(commit);
                    },
                  },
                ]
              : []),
            ...(adapter.prepareRemoveNewFieldDraft && adapter.removableNewFieldNames?.().length
              ? [removeMetadataNewFieldDraftCapability(adapter)]
              : []),
            ...(canAddFieldDraft(adapter) ? [addMetadataFieldDraftCapability(adapter)] : []),
            ...(canUpdateFieldDraft(adapter) ? [updateMetadataFieldDraftCapability(adapter)] : []),
            ...(adapter.prepareReferenceUpdate && adapter.editableReferenceFieldNames?.().length
              ? [updateMetadataReferenceDraftCapability(adapter)]
              : []),
            ...(adapter.referenceAffectDirectory &&
            (canAddPropertyFieldDraft(adapter) || adapter.editableReferenceFieldNames?.().length)
              ? [referenceAffectDirectoryCapability(adapter)]
              : []),
            ...(canAddPropertyFieldDraft(adapter)
              ? [
                  findMetadataFieldTargetsCapability(adapter),
                  addMetadataPropertyFieldDraftCapability(adapter),
                ]
              : []),
            ...(hasChanges(adapter.proposal()) ? [previewMetadataDraftCapability(adapter)] : []),
            ...(adapter.prepareConfirmation &&
            (hasChanges(adapter.proposal()) || adapter.summary().childCandidate)
              ? [prepareMetadataConfirmationCapability(adapter)]
              : []),
          ],
    requestTurn,
  };
}

function removeMetadataNewFieldDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<{ fieldName: string }> {
  const fieldNames = adapter.removableNewFieldNames!();
  return {
    effect: 'configuration-draft',
    descriptor: {
      code: 'configuration.remove-metadata-new-field-draft',
      description:
        'Remove one newly added, unsaved field from the shared batch when the user asks to revise it; preserve all other fields and capability selections. Cannot remove persisted fields. Finish the current individual field editor first.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['fieldName'],
        properties: { fieldName: { type: 'string', enum: fieldNames } },
      },
    },
    parseInput(input) {
      const value = input as { fieldName?: unknown };
      if (
        !value ||
        typeof value !== 'object' ||
        Array.isArray(value) ||
        Object.keys(value).some((key) => key !== 'fieldName') ||
        typeof value.fieldName !== 'string' ||
        !fieldNames.includes(value.fieldName)
      )
        throw new AssistantCapabilityUsageError('Only a current newly added field can be removed');
      return { fieldName: value.fieldName };
    },
    async execute(input, context) {
      const commit = adapter.prepareRemoveNewFieldDraft!(input);
      return context.applyEffect(commit);
    },
  };
}

function prepareMetadataCapabilityDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability {
  const available =
    adapter.summary().selectedRelation?.capabilities?.filter((fact) => fact.configurable && !fact.enabled) ??
    [];
  return {
    effect: 'configuration-draft',
    descriptor: {
      code: 'configuration.prepare-metadata-capability-draft',
      description:
        'Select a standard metadata capability in the shared visible draft. Use the current model capability facts and defaults. Platform creates its managed fields on save; never add same-name business fields. selected=false only abandons an unsaved selection; cannot disable persisted capabilities. Preserve existing field drafts; review and confirm the whole change set.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['capability', 'selected'],
        properties: {
          capability: { type: 'string', enum: available.map((fact) => fact.capability) },
          selected: { type: 'boolean' },
        },
      },
    },
    parseInput(input) {
      if (
        !isRecord(input) ||
        Object.keys(input).some((key) => !['capability', 'selected'].includes(key)) ||
        typeof input.selected !== 'boolean' ||
        !available.some((fact) => fact.capability === input.capability)
      )
        throw new AssistantCapabilityUsageError('请选择当前可配置的基础能力。');
      return { capability: input.capability as string, selected: input.selected };
    },
    async execute(input, context) {
      const commit = adapter.prepareCapabilityDraft!(input as { capability: string; selected: boolean });
      return context.applyEffect(commit);
    },
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
        'Prepare the missing main entity in the standard metadata editor. Available only when the module has no metadata relations. The standard editor derives its identifier from the module; provide the business title. This only prepares a visible draft, never creates storage. Open the metadata editor and ask the user to review and save there; then reread metadata before adding fields. storageDefaultsOnSave lists blank optional settings that the platform fills on save; these are not missing required inputs and do not require a user decision about storage. Preserve existing manual storage settings.',
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
        'Prepare or revise one child table in the shared editor. parentRelationId must identify the intended parent from current metadata facts and match the selected relation. For sibling tables, select their common parent before preparing each child; an existing child cannot implicitly serve as that parent. A wrong unsaved candidate must be reviewed or discarded before changing the selected parent. Does not create storage. The result reports the actual candidate parent. Use prepare-metadata-apply for human confirmation; an empty child is not business completion.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['alias', 'title', 'parentRelationId'],
        properties: {
          alias: { type: 'string', pattern: '^[a-z][a-z0-9_]{0,62}$', maxLength: 63 },
          title: { type: 'string', minLength: 1, maxLength: 120 },
          parentRelationId: { type: 'string', minLength: 1, maxLength: 100 },
        },
      },
    },
    parseInput(input) {
      if (
        !isRecord(input) ||
        Object.keys(input).some((key) => !['alias', 'title', 'parentRelationId'].includes(key))
      )
        throw new AssistantCapabilityUsageError('请提供明细名称和标识');
      const alias = boundedString(input.alias, 'alias', 63, true);
      if (!/^[a-z][a-z0-9_]{0,62}$/.test(alias)) throw new AssistantCapabilityUsageError('明细标识格式无效');
      return {
        alias,
        title: boundedString(input.title, 'title', 120, true),
        parentRelationId: boundedString(input.parentRelationId, 'parentRelationId', 100, true),
      };
    },
    async execute(input, context) {
      const value = input as { alias: string; title: string; parentRelationId: string };
      const selected = adapter.summary().selectedRelation;
      if (!selected || selected.relationId !== value.parentRelationId)
        throw new AssistantCapabilityUsageError(
          '明细的目标父级与当前选择不一致；请核对父级，已有错误候选时先审阅或放弃，再选择正确的父级。',
        );
      return context.applyEffect(adapter.prepareChildDraft!({ alias: value.alias, title: value.title }));
    },
    present(output) {
      const candidate = output as { title: string; parentTitle?: string };
      return {
        title: '明细结构草稿已准备',
        lines: [
          `明细：${candidate.title}`,
          `所属父级：${candidate.parentTitle || '当前选中的元数据，请在编辑器核对'}`,
          '尚未保存；父级关系以当前草稿为准。',
        ],
      };
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
            defaultValue: { type: ['string', 'null'], maxLength: 512 },
            selectionMode: {
              type: 'string',
              enum: adapter.fieldSpecAliases().includes('json_set') ? ['SINGLE', 'MULTIPLE'] : ['SINGLE'],
            },
          }
        : {}),
      ...(kind === 'MODULE_REFERENCE'
        ? {
            affectMappings: {
              type: 'array',
              maxItems: 8,
              uniqueItems: true,
              items: {
                type: 'string',
                maxLength: 127,
                pattern: '^[a-z][A-Za-z0-9]{0,62}:[a-z][A-Za-z0-9]{0,62}$',
              },
            },
          }
        : {}),
      required: { type: 'boolean' },
    },
  }));
}

function referenceAffectDirectoryCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability {
  return {
    effect: 'read',
    descriptor: {
      code: 'configuration.describe-reference-affects',
      description:
        'Read selectable source fields from a discovered reference target and saved destination fields in the current entity. Use sourceField:destinationField mappings when adding a reference. Selection copies a value into the draft only; reopening or saving never refreshes it from the source. Save destination fields before configuring mappings.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['target'],
        properties: {
          target: { type: 'string', minLength: 1, maxLength: 128 },
          offset: { type: 'integer', minimum: 0 },
        },
      },
    },
    parseInput(input) {
      if (
        !isRecord(input) ||
        Object.keys(input).some((key) => !['target', 'offset'].includes(key)) ||
        (input.offset !== undefined && (!Number.isSafeInteger(input.offset) || Number(input.offset) < 0))
      )
        throw new AssistantCapabilityUsageError('请提供引用目标与有效分页位置。');
      return { target: boundedString(input.target, 'target', 128, true), offset: Number(input.offset ?? 0) };
    },
    async execute(value, context) {
      const input = value as { target: string; offset: number };
      const directory = await adapter.referenceAffectDirectory!(input.target, context.signal);
      const total = Math.max(directory.sources.length, directory.destinations.length);
      return {
        sources: directory.sources.slice(input.offset, input.offset + 40),
        destinations: directory.destinations.slice(input.offset, input.offset + 40),
        sourceCount: directory.sources.length,
        destinationCount: directory.destinations.length,
        nextOffset: input.offset + 40 < total ? input.offset + 40 : null,
      };
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
        'Update one editable ordinary business field as a visible, unsaved candidate, including one field in an unsaved batch plan while preserving its other fields. defaultValue is a fixed initial value encoded as text (e.g. "1"), not a formula. Omit it to preserve the default; null clears a local default without an inherited base default. Value ranges use standard validation rules. When an individual field editor is open, revise only that current candidate; first describe it to inspect the user’s latest changes. The user can review, revise or cancel it before confirming the standard change-set.',
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
          titleField: {
            type: 'boolean',
            description:
              'Record display name: only the standard title/title field can be a title field. Do not turn an existing ordinary field into the record name; create the standard field or revise its business label.',
          },
          defaultValue: { type: ['string', 'null'], maxLength: 512 },
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

function updateMetadataReferenceDraftCapability(
  adapter: MetadataGovernanceAssistantAdapter,
): AssistantCapability<UpdateMetadataReferenceDraftInput> {
  const fieldNames = adapter.editableReferenceFieldNames!();
  return {
    effect: 'configuration-draft',
    descriptor: {
      code: 'configuration.update-metadata-reference-draft',
      description:
        'Revise selection-copy mappings or enabled-target restriction on an existing editable module-reference field using the shared visible candidate. Preserve target identity, key/label fields, cardinality, projections and other settings. First read the current reference facts and source/destination directory. Selection copies a snapshot; reopening or saving does not refresh it. Omit settings to preserve them; [] clears selection-copy mappings. Does not save. Review and confirm through the standard change-set.',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['fieldName'],
        properties: {
          fieldName: { type: 'string', enum: fieldNames },
          requireEnabled: { type: 'boolean' },
          affectMappings: {
            type: 'array',
            maxItems: 8,
            uniqueItems: true,
            items: {
              type: 'string',
              maxLength: 127,
              pattern: '^[a-z][A-Za-z0-9]{0,62}:[a-z][A-Za-z0-9]{0,62}$',
            },
          },
        },
      },
    },
    parseInput(input) {
      if (
        !isRecord(input) ||
        Object.keys(input).some((key) => !['fieldName', 'requireEnabled', 'affectMappings'].includes(key)) ||
        typeof input.fieldName !== 'string' ||
        !fieldNames.includes(input.fieldName) ||
        (input.requireEnabled !== undefined && typeof input.requireEnabled !== 'boolean') ||
        (input.affectMappings !== undefined &&
          (!Array.isArray(input.affectMappings) ||
            input.affectMappings.length > 8 ||
            new Set(input.affectMappings).size !== input.affectMappings.length ||
            input.affectMappings.some(
              (mapping) =>
                typeof mapping !== 'string' ||
                !/^[a-z][A-Za-z0-9]{0,62}:[a-z][A-Za-z0-9]{0,62}$/.test(mapping),
            )))
      )
        throw new AssistantCapabilityUsageError('请提供当前引用字段及有效的回填或启用限制设置。');
      if (input.requireEnabled === undefined && input.affectMappings === undefined)
        throw new AssistantCapabilityUsageError('请至少提供一项引用设置。');
      return input as unknown as UpdateMetadataReferenceDraftInput;
    },
    async execute(input, context) {
      const commit = await adapter.prepareReferenceUpdate!(input, context.signal);
      if (!context.isCurrent()) throw new AssistantCapabilityUsageError('引用准备已过期，请重新核实。');
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
        'Add one ordinary business field, including an addition to the current batch, as a visible unsaved candidate; preserve other fields. defaultValue is a fixed initial value encoded as text (e.g. "1"), not a formula; value ranges use standard validation rules. Stored result columns are ordinary writable metadata. Calculation rules provide their read-only form projection; apply those rules before publishing the form. The user must confirm the standard change-set to save.',
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
            description:
              'Optional technical name. For titleField=true omit this value so the platform generates title, or use title exactly. Do not ask the business user to supply it.',
          },
          fieldSpecAlias: { type: 'string', enum: aliases },
          required: { type: 'boolean' },
          unique: { type: 'boolean' },
          indexed: { type: 'boolean' },
          sortable: { type: 'boolean' },
          titleField: {
            type: 'boolean',
            description:
              'True for the record display name, using the standard title field and title column. Other business information must remain ordinary fields.',
          },
          defaultValue: { type: ['string', 'null'], maxLength: 512 },
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
      factsAvailable: summary.factsAvailable,
      submissionStatus: summary.submissionStatus,
      committedNeedsReload: summary.committedNeedsReload,
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
        'Describe the current module metadata model, selected relation, visible fields with their current required, unique, index, sorting, title and enabled settings, and local draft state. Includes staged fields in a batch plan. fieldsSource distinguishes UNSAVED_CANDIDATE from SAVED_CONFIGURATION; candidate fields are not evidence of publication. Compare fieldName, columnName and fieldSpecAlias separately when a preview rejects a record-name field. Reuse satisfied settings. It does not change configuration.',
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
        ['显示名称', '必填', '唯一', '启用', '固定默认值'].includes(change.property),
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
    'defaultValue',
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
    ...optionalDefaultValue(input),
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
    'defaultValue',
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
    ...optionalDefaultValue(input),
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
  if (kind === 'MODULE_REFERENCE' && input.defaultValue !== undefined)
    throw new AssistantCapabilityUsageError('引用初值应使用关联初始化。');
  const keyword = boundedString(input.keyword, 'keyword', 100, false);
  return { kind, ...(keyword ? { keyword } : {}) };
}

function parseAddPropertyFieldDraftInput(
  input: unknown,
  dictionarySelectionModes: Array<'SINGLE' | 'MULTIPLE'>,
): AddMetadataPropertyFieldDraftInput {
  if (!isRecord(input)) throw new AssistantCapabilityUsageError('Capability input must be an object');
  const allowed = new Set([
    'kind',
    'title',
    'fieldName',
    'target',
    'selectionMode',
    'required',
    'affectMappings',
    'defaultValue',
  ]);
  if (Object.keys(input).some((key) => !allowed.has(key)))
    throw new AssistantCapabilityUsageError(
      'Capability input contains unsupported metadata property field properties',
    );
  const kind = metadataPropertyFieldKind(input.kind);
  if (kind === 'MODULE_REFERENCE' && input.defaultValue !== undefined)
    throw new AssistantCapabilityUsageError('引用初值应使用关联初始化。');
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
  if (
    input.affectMappings !== undefined &&
    (kind !== 'MODULE_REFERENCE' ||
      !Array.isArray(input.affectMappings) ||
      input.affectMappings.length > 8 ||
      new Set(input.affectMappings).size !== input.affectMappings.length ||
      input.affectMappings.some(
        (item) =>
          typeof item !== 'string' ||
          item.length > 127 ||
          item.split(':').length !== 2 ||
          !item.split(':').every(isPlatformFieldName),
      ))
  )
    throw new AssistantCapabilityUsageError('选择回填必须使用最多八项不同的来源字段:当前字段映射。');
  const required = optionalBooleanProperties(input, ['required']).required;
  return {
    kind,
    title,
    ...(fieldName ? { fieldName } : {}),
    target,
    ...(kind === 'DICTIONARY'
      ? { selectionMode: selectionMode ?? 'SINGLE', ...optionalDefaultValue(input) }
      : {}),
    ...(required !== undefined ? { required } : {}),
    ...(input.affectMappings !== undefined ? { affectMappings: input.affectMappings as string[] } : {}),
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

function optionalDefaultValue(input: Record<string, unknown>): { defaultValue?: string | null } {
  if (input.defaultValue === undefined) return {};
  if (
    input.defaultValue !== null &&
    (typeof input.defaultValue !== 'string' || input.defaultValue.length > 512)
  )
    throw new AssistantCapabilityUsageError('固定默认值须为最多 512 字符的文本，清除时使用 null。');
  return { defaultValue: input.defaultValue as string | null };
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
