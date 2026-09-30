import {
  AssistantCapabilityUsageError,
  emptyAssistantCapabilityInputSchema,
  parseEmptyAssistantCapabilityInput,
  type AssistantCapability,
  type AssistantOperationProposal,
  type AssistantSurface,
  type AssistantTurnRequester,
} from '@muyun/web-core';
import type { PageComposerFieldProperties } from './pageCompositionDraftState';
import type { PageCompositionCandidateInput } from './pageCompositionCandidate';

export interface PageCompositionAssistantAdapter {
  describe(): { moduleAlias: string; title?: string; editable: boolean; [key: string]: unknown };
  relations?(input: { relationCode?: string; offset: number }): unknown;
  candidate(): { changes: string[]; [key: string]: unknown };
  prepareEditing?(): AssistantOperationProposal;
  prepareConfirmation?(signal: AbortSignal): Promise<AssistantOperationProposal>;
  prepare(input: PageCompositionCandidateInput): () => unknown;
  preview(signal: AbortSignal): Promise<{ valid: boolean; errors: string[] }>;
}

export function createPageCompositionAssistantSurface(
  adapter: PageCompositionAssistantAdapter,
  requestTurn: AssistantTurnRequester,
  contributedCapabilities: () => AssistantCapability[] = () => [],
): AssistantSurface {
  let prepared: AssistantOperationProposal | undefined;
  const read = (code: string, description: string, value: () => unknown): AssistantCapability => ({
    effect: 'read',
    descriptor: { code, description, inputSchema: emptyAssistantCapabilityInputSchema() },
    parseInput: parseEmptyAssistantCapabilityInput,
    async execute() {
      return value();
    },
  });
  return {
    describe: () => ({
      surface: 'page-composition',
      title: adapter.describe().title,
      facts: { moduleAlias: adapter.describe().moduleAlias, editable: adapter.describe().editable },
    }),
    capabilities: () => [
      ...contributedCapabilities(),
      ...(adapter.prepareEditing && !adapter.describe().editable && adapter.describe().canStartEditing
        ? [
            {
              effect: 'read' as const,
              descriptor: {
                code: 'configuration.prepare-page-editing',
                description:
                  'Prepare confirmation to initialize or resume the standard page draft. This only prepares editing; it does not publish or change the running page.',
                inputSchema: emptyAssistantCapabilityInputSchema(),
              },
              parseInput: parseEmptyAssistantCapabilityInput,
              async execute(_input: unknown, context: Parameters<AssistantCapability['execute']>[1]) {
                context.commitInternalState(() => {
                  prepared = adapter.prepareEditing!();
                });
                return { pendingConfirmation: true, published: false };
              },
              propose: () => prepared!,
            },
          ]
        : []),
      ...(adapter.prepareConfirmation && adapter.describe().editable
        ? [
            {
              effect: 'read' as const,
              descriptor: {
                code: 'configuration.prepare-page-publication',
                description:
                  'Validate the complete shared page candidate and prepare human confirmation to save and activate it. Includes manual edits; never publishes before the user confirms.',
                inputSchema: emptyAssistantCapabilityInputSchema(),
              },
              parseInput: parseEmptyAssistantCapabilityInput,
              async execute(_input: unknown, context: Parameters<AssistantCapability['execute']>[1]) {
                const proposal = await adapter.prepareConfirmation!(context.signal);
                context.commitInternalState(() => {
                  prepared = proposal;
                });
                return { pendingConfirmation: true, published: false };
              },
              propose: () => prepared!,
            },
          ]
        : []),
      ...(adapter.relations
        ? [
            {
              effect: 'read' as const,
              descriptor: {
                code: 'configuration.describe-page-relations',
                description:
                  'Read existing direct child relations, or a named child’s field directory. Use nextOffset to continue; omitted or oversized entries are not absent. Does not create metadata or change layout.',
                inputSchema: {
                  type: 'object',
                  additionalProperties: false,
                  properties: {
                    relationCode: { type: 'string', minLength: 1, maxLength: 255 },
                    offset: { type: 'integer', minimum: 0 },
                  },
                },
              },
              parseInput(input: unknown) {
                if (
                  !record(input) ||
                  Object.keys(input).some((key) => !['relationCode', 'offset'].includes(key)) ||
                  (input.relationCode !== undefined &&
                    (typeof input.relationCode !== 'string' ||
                      !input.relationCode.trim() ||
                      input.relationCode.length > 255)) ||
                  (input.offset !== undefined &&
                    (!Number.isSafeInteger(input.offset) || Number(input.offset) < 0))
                )
                  throw new AssistantCapabilityUsageError('Invalid child catalog input');
                return {
                  relationCode: input.relationCode as string | undefined,
                  offset: Number(input.offset ?? 0),
                };
              },
              async execute(input: { relationCode?: string; offset: number }) {
                return adapter.relations!(input);
              },
            } satisfies AssistantCapability<{ relationCode?: string; offset: number }>,
          ]
        : []),
      read(
        'configuration.describe-page-composition',
        'Read the current template, available fields, root placements and editing constraints before proposing changes. Only listed fields can be used. Read availableRelations and current relations for child layouts. Grouped fields, actions and omitted regions are preserved.',
        () => adapter.describe(),
      ),
      read(
        'configuration.describe-page-candidate',
        'Read current visible candidate changes relative to the saved draft. These are unsaved page configuration changes, not metadata changes.',
        () => adapter.candidate(),
      ),
      ...(adapter.describe().editable
        ? [
            {
              effect: 'configuration-draft' as const,
              descriptor: {
                code: 'configuration.revise-page-candidate',
                description:
                  'Revise the visible unsaved page candidate using existing fields. Each supplied region replaces its complete ordered ROOT field list (empty removes root placements); omitted regions and unspecified field properties are preserved. Groups are untouched. Supplied relations replace the complete ordered child layout (empty removes child display, never data); each child needs explicit existing fields from its own directory. Omitted relations preserve all children. Do not send arbitrary UI JSON. Preview after revision, then prepare publication confirmation if the user wants to save. Never claim the candidate is already published.',
                inputSchema: candidateInputSchema(),
              },
              parseInput: parsePageCompositionCandidateInput,
              async execute(input: PageCompositionCandidateInput, context) {
                let commit: () => unknown;
                try {
                  commit = adapter.prepare(input);
                } catch (cause) {
                  if (cause instanceof AssistantCapabilityUsageError) throw cause;
                  throw new AssistantCapabilityUsageError(
                    cause instanceof Error ? cause.message : 'Invalid page candidate',
                  );
                }
                return context.applyEffect(commit);
              },
            } satisfies AssistantCapability<PageCompositionCandidateInput>,
          ]
        : []),
      {
        effect: 'read' as const,
        descriptor: {
          code: 'configuration.preview-page-candidate',
          description:
            'Validate the current visible page candidate through the standard template compiler without saving or activating. Returns candidate changes and validation; use a valid result to prepare publication confirmation; do not repeat unless the candidate changes.',
          inputSchema: emptyAssistantCapabilityInputSchema(),
        },
        parseInput: parseEmptyAssistantCapabilityInput,
        async execute(_input, context) {
          const result = await adapter.preview(context.signal);
          if (!context.isCurrent())
            throw new AssistantCapabilityUsageError('Page candidate preview is no longer current');
          return { ...result, candidate: adapter.candidate() };
        },
        present(result) {
          return {
            title: '页面候选预检（尚未生效）',
            lines: [
              result.valid ? '模板校验通过，仍需人工保存并生效。' : '模板校验未通过。',
              ...result.errors.slice(0, 8),
              ...result.candidate.changes.slice(0, 10),
            ].map((line) => line.slice(0, 500)),
          };
        },
      } satisfies AssistantCapability<
        Record<string, never>,
        {
          valid: boolean;
          errors: string[];
          candidate: ReturnType<PageCompositionAssistantAdapter['candidate']>;
        }
      >,
    ],
    requestTurn,
  };
}

function candidateInputSchema(): Record<string, unknown> {
  const region = (form: boolean) => ({
    type: 'array',
    maxItems: 40,
    items: {
      type: 'object',
      additionalProperties: false,
      required: ['fieldName'],
      properties: {
        fieldName: { type: 'string', minLength: 1, maxLength: 255 },
        properties: {
          type: 'object',
          additionalProperties: false,
          properties: {
            label: { type: 'string', maxLength: 100 },
            ...(form
              ? { columnSpan: { type: 'integer', enum: [1, 2] }, readOnly: { type: 'boolean' } }
              : {
                  width: { type: 'string', pattern: '^([1-9][0-9]*(px|%))?$' },
                  align: { type: 'string', enum: ['left', 'center', 'right'] },
                }),
          },
        },
      },
    },
  });
  return {
    type: 'object',
    additionalProperties: false,
    minProperties: 1,
    properties: {
      list: region(false),
      form: region(true),
      detail: region(true),
      relations: {
        type: 'array',
        maxItems: 16,
        items: {
          type: 'object',
          additionalProperties: false,
          required: ['relationCode', 'fields'],
          properties: {
            relationCode: { type: 'string', minLength: 1, maxLength: 255 },
            title: { type: 'string', minLength: 1, maxLength: 100 },
            fields: { ...region(false), minItems: 1 },
          },
        },
      },
      quickSearchFields: {
        type: 'array',
        maxItems: 40,
        uniqueItems: true,
        items: { type: 'string', minLength: 1, maxLength: 255 },
      },
    },
  };
}

export function parsePageCompositionCandidateInput(input: unknown): PageCompositionCandidateInput {
  const fail = (message: string): never => {
    throw new AssistantCapabilityUsageError(message);
  };
  if (
    !record(input) ||
    !Object.keys(input).length ||
    Object.keys(input).some(
      (key) => !['list', 'form', 'detail', 'quickSearchFields', 'relations'].includes(key),
    )
  )
    return fail('Unsupported page candidate input');
  const result: PageCompositionCandidateInput = {};
  for (const region of ['list', 'form', 'detail', 'quickSearchFields'] as const) {
    const value = input[region];
    if (value === undefined) continue;
    if (!Array.isArray(value) || value.length > 40) return fail('Each page region accepts at most 40 fields');
    const names = new Set<string>();
    const entries = value.map((item) => {
      const rawName = region === 'quickSearchFields' ? item : record(item) ? item.fieldName : undefined;
      if (typeof rawName !== 'string' || !rawName.trim() || rawName.length > 255)
        return fail('Invalid fieldName');
      const fieldName = rawName.trim();
      if (names.has(fieldName)) return fail('Duplicate page field');
      names.add(fieldName);
      if (region === 'quickSearchFields') return { fieldName };
      if (!record(item) || Object.keys(item).some((key) => key !== 'fieldName' && key !== 'properties'))
        return fail('Unsupported page field input');
      if (item.properties === undefined) return { fieldName };
      const properties = item.properties;
      const allowed = region === 'list' ? ['label', 'width', 'align'] : ['label', 'columnSpan', 'readOnly'];
      if (!record(properties) || Object.keys(properties).some((key) => !allowed.includes(key)))
        return fail('Unsupported presentation property');
      if (
        properties.label !== undefined &&
        (typeof properties.label !== 'string' || properties.label.length > 100)
      )
        return fail('Invalid field label');
      if (
        properties.width !== undefined &&
        (typeof properties.width !== 'string' ||
          properties.width.length > 20 ||
          !/^([1-9][0-9]*(px|%))?$/.test(properties.width))
      )
        return fail('Invalid column width');
      if (properties.align !== undefined && !['left', 'center', 'right'].includes(String(properties.align)))
        return fail('Invalid alignment');
      if (properties.columnSpan !== undefined && properties.columnSpan !== 1 && properties.columnSpan !== 2)
        return fail('Invalid column span');
      if (properties.readOnly !== undefined && typeof properties.readOnly !== 'boolean')
        return fail('Invalid read-only value');
      return { fieldName, properties: { ...properties } as PageComposerFieldProperties };
    });
    if (region === 'quickSearchFields') result.quickSearchFields = entries.map((entry) => entry.fieldName);
    else result[region] = entries;
  }
  if (input.relations !== undefined) {
    if (!Array.isArray(input.relations) || input.relations.length > 16)
      return fail('At most 16 child layouts');
    const codes = new Set<string>();
    result.relations = input.relations.map((relation) => {
      if (
        !record(relation) ||
        Object.keys(relation).some((key) => !['relationCode', 'title', 'fields'].includes(key)) ||
        typeof relation.relationCode !== 'string' ||
        !relation.relationCode.trim() ||
        relation.relationCode.length > 255
      )
        return fail('Invalid child relation');
      const relationCode = relation.relationCode.trim();
      if (codes.has(relationCode)) return fail('Duplicate child relation');
      codes.add(relationCode);
      if (
        relation.title !== undefined &&
        (typeof relation.title !== 'string' || !relation.title.trim() || relation.title.length > 100)
      )
        return fail('Invalid child title');
      if (!Array.isArray(relation.fields) || !relation.fields.length)
        return fail('Child layouts require explicit fields');
      const fields = parsePageCompositionCandidateInput({ list: relation.fields }).list!;
      return {
        relationCode,
        fields,
        ...(relation.title === undefined ? {} : { title: (relation.title as string).trim() }),
      };
    });
  }
  return result;
}
function record(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value));
}
