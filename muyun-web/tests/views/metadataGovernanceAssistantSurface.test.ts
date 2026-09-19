import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createMetadataGovernanceAssistantSurface,
  type MetadataGovernanceAssistantAdapter,
} from '@/views/metadataGovernanceAssistantSurface';
import type { MetadataModelChangeSetProposal } from '@/views/metadataModelEditSession';

const proposal: MetadataModelChangeSetProposal = {
  relationDrafts: [
    {
      relationId: 'relation-main',
      expectedMetadataVersion: 3,
      fieldDrafts: [
        {
          operation: 'ADD',
          field: { fieldName: 'dailySummary', title: '日报摘要', fieldSpecAlias: 'string' },
        },
      ],
    },
  ],
  relationOrders: [],
  fieldOrders: [],
};
const applyEffectSpy = vi.fn();

describe('metadata governance assistant surface', () => {
  beforeEach(() => applyEffectSpy.mockClear());

  it('describes a bounded metadata context and keeps workbench capabilities', async () => {
    const adapter = fixture();
    const contributed = capability('navigation.find-menu');
    const surface = createMetadataGovernanceAssistantSurface(adapter, vi.fn(), () => [contributed]);

    expect(surface.describe()).toEqual({
      surface: 'metadata-governance',
      title: '考试管理 · 元数据',
      facts: {
        moduleAlias: 'education.exam',
        relationCount: 1,
        selectedRelationId: 'relation-main',
        editing: true,
        dirty: true,
      },
    });
    expect(surface.capabilities().map(({ descriptor }) => descriptor.code)).toEqual([
      'navigation.find-menu',
      'configuration.describe-metadata-model',
      'configuration.add-metadata-field-draft',
      'configuration.update-metadata-field-draft',
      'configuration.preview-metadata-draft',
    ]);

    const describe = surface
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'configuration.describe-metadata-model')!;
    await expect(describe.execute(describe.parseInput({}), executionContext())).resolves.toEqual(
      adapter.summary(),
    );
    expect(() => describe.parseInput({ unexpected: true })).toThrow('Capability input must be empty');
  });

  it('stages a validated ordinary field update through the guarded page effect boundary', async () => {
    const adapter = fixture();
    const update = createMetadataGovernanceAssistantSurface(adapter, vi.fn())
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'configuration.update-metadata-field-draft')!;
    const context = executionContext();

    await expect(
      update.execute(update.parseInput({ fieldName: 'title', title: '考试名称', indexed: true }), context),
    ).resolves.toEqual({
      relationId: 'relation-main',
      fieldName: 'title',
      title: '考试名称',
      fieldSpecAlias: 'string',
    });
    expect(adapter.updateFieldDraft).toHaveBeenCalledWith({
      fieldName: 'title',
      title: '考试名称',
      indexed: true,
    });
    expect(applyEffectSpy).toHaveBeenCalledOnce();
    expect(() => update.parseInput({ fieldName: 'unknown', title: '未知' })).toThrow(
      'Metadata field is unavailable for editing',
    );
    expect(() => update.parseInput({ fieldName: 'title', fieldSpecAlias: 'unknown' })).toThrow(
      'Unknown metadata field specification',
    );
    expect(() => update.parseInput({ fieldName: 'title' })).toThrow(
      'At least one metadata field change is required',
    );
  });

  it('stages a validated ordinary field through the guarded page effect boundary', async () => {
    const adapter = fixture();
    const surface = createMetadataGovernanceAssistantSurface(adapter, vi.fn());
    const add = surface
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'configuration.add-metadata-field-draft')!;
    const context = executionContext();

    await expect(
      add.execute(
        add.parseInput({
          title: '考试备注',
          fieldSpecAlias: 'string',
          required: true,
          indexed: false,
        }),
        context,
      ),
    ).resolves.toEqual({
      relationId: 'relation-main',
      fieldName: 'examRemark',
      columnName: 'exam_remark',
      title: '考试备注',
      fieldSpecAlias: 'string',
    });
    expect(applyEffectSpy).toHaveBeenCalledOnce();
    expect(adapter.addFieldDraft).toHaveBeenCalledWith({
      title: '考试备注',
      fieldSpecAlias: 'string',
      required: true,
      indexed: false,
    });
    expect(() => add.parseInput({ title: '未知', fieldSpecAlias: 'unknown' })).toThrow(
      'Unknown metadata field specification',
    );
    expect(() =>
      add.parseInput({ title: '备注', fieldName: 'customer_name', fieldSpecAlias: 'string' }),
    ).toThrow('fieldName must use lower camel case');
    expect(() => add.parseInput({ title: '值', fieldName: 'values', fieldSpecAlias: 'string' })).toThrow(
      'fieldName is reserved by the dynamic record protocol',
    );
    expect(() =>
      add.parseInput({ title: '备注', fieldName: `a${'b'.repeat(63)}`, fieldSpecAlias: 'string' }),
    ).toThrow('no longer than 63 characters');
    expect(() => add.parseInput({ title: '备注', fieldSpecAlias: 'string', systemManaged: true })).toThrow(
      'unsupported metadata field properties',
    );
  });

  it('resolves governed targets before staging a reference or dictionary field candidate', async () => {
    const adapter = fixture();
    adapter.findFieldTargets = vi.fn(async () => ({
      targets: [{ target: 'iam.user', title: '用户' }],
      truncated: false,
    }));
    adapter.preparePropertyFieldDraft = vi.fn(async (input) => ({
      relationId: 'relation-main',
      kind: input.kind,
      title: input.title,
      fieldName: 'ownerId',
      columnName: 'owner_id',
      fieldSpecAlias: 'string',
      required: false,
      reference: {
        targetModuleAlias: 'iam.user',
        targetMetadataId: 'metadata-user',
        targetKeyField: 'id',
        targetLabelField: 'displayName',
      },
    }));
    adapter.commitPropertyFieldDraft = vi.fn((prepared) => ({
      relationId: prepared.relationId,
      kind: prepared.kind,
      fieldName: prepared.fieldName,
      columnName: prepared.columnName,
      title: prepared.title,
      fieldSpecAlias: prepared.fieldSpecAlias,
      target: prepared.reference!.targetModuleAlias,
    }));
    const surface = createMetadataGovernanceAssistantSurface(adapter, vi.fn());
    const lookup = surface
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'configuration.find-metadata-field-targets')!;
    const add = surface
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'configuration.add-metadata-property-field-draft')!;
    const context = executionContext();

    await expect(
      lookup.execute(lookup.parseInput({ kind: 'MODULE_REFERENCE', keyword: '用户' }), context),
    ).resolves.toEqual({
      kind: 'MODULE_REFERENCE',
      targets: [{ target: 'iam.user', title: '用户' }],
      truncated: false,
    });
    await expect(
      add.execute(
        add.parseInput({
          kind: 'MODULE_REFERENCE',
          title: '负责人',
          target: 'iam.user',
        }),
        context,
      ),
    ).resolves.toEqual({
      relationId: 'relation-main',
      kind: 'MODULE_REFERENCE',
      fieldName: 'ownerId',
      columnName: 'owner_id',
      title: '负责人',
      fieldSpecAlias: 'string',
      target: 'iam.user',
    });
    expect(adapter.preparePropertyFieldDraft).toHaveBeenCalledWith(
      { kind: 'MODULE_REFERENCE', title: '负责人', target: 'iam.user' },
      context.signal,
    );
    expect(applyEffectSpy).toHaveBeenCalledOnce();
    expect(() =>
      add.parseInput({
        kind: 'MODULE_REFERENCE',
        title: '负责人',
        target: 'iam.user',
        selectionMode: 'MULTIPLE',
      }),
    ).toThrow('selectionMode is only supported for dictionary fields');
    expect(
      add.parseInput({
        kind: 'DICTIONARY',
        title: '状态',
        target: 'education.status',
      }),
    ).toEqual({
      kind: 'DICTIONARY',
      title: '状态',
      target: 'education.status',
      selectionMode: 'SINGLE',
    });
  });

  it('previews the exact current candidate without exposing a publish capability', async () => {
    const adapter = fixture();
    const surface = createMetadataGovernanceAssistantSurface(adapter, vi.fn());
    const preview = surface
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'configuration.preview-metadata-draft')!;
    const context = executionContext();

    await expect(preview.execute(preview.parseInput({}), context)).resolves.toEqual({
      valid: true,
      fieldImpacts: [
        {
          operation: 'ADD',
          fieldName: 'dailySummary',
          columnName: 'daily_summary',
          platformManaged: false,
          description: '新增日报摘要',
        },
      ],
      schemaImpacts: [
        {
          operation: 'ADD_COLUMN',
          schemaName: 'public',
          tableName: 'exam',
          columnName: 'daily_summary',
          description: '新增日报摘要物理列',
        },
      ],
      orderImpacts: [],
      warnings: [],
      errors: [],
    });
    expect(vi.mocked(adapter.preview)).toHaveBeenCalledWith(proposal, context.signal);
    expect(surface.capabilities().map(({ descriptor }) => descriptor.code)).not.toContain(
      'configuration.publish',
    );
  });

  it('does not advertise preview without a changed candidate and rejects stale results', async () => {
    const adapter = fixture({ relationDrafts: [], relationOrders: [], fieldOrders: [] });
    expect(
      createMetadataGovernanceAssistantSurface(adapter, vi.fn())
        .capabilities()
        .map(({ descriptor }) => descriptor.code),
    ).toEqual([
      'configuration.describe-metadata-model',
      'configuration.add-metadata-field-draft',
      'configuration.update-metadata-field-draft',
    ]);

    const current = fixture();
    const preview = createMetadataGovernanceAssistantSurface(current, vi.fn())
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'configuration.preview-metadata-draft')!;
    await expect(
      preview.execute(preview.parseInput({}), { ...executionContext(), isCurrent: () => false }),
    ).rejects.toThrow('Metadata candidate preview is no longer current');
  });

  it('does not advertise field drafting while a metadata editor is already open', () => {
    const adapter = fixture();
    vi.mocked(adapter.summary).mockReturnValue({
      ...adapter.summary(),
      draft: { active: true, dirty: false, editorOpen: true },
    });
    expect(
      createMetadataGovernanceAssistantSurface(adapter, vi.fn())
        .capabilities()
        .map(({ descriptor }) => descriptor.code),
    ).not.toContain('configuration.add-metadata-field-draft');
    expect(
      createMetadataGovernanceAssistantSurface(adapter, vi.fn())
        .capabilities()
        .map(({ descriptor }) => descriptor.code),
    ).not.toContain('configuration.update-metadata-field-draft');
  });

  it('keeps the complete field-spec catalog in the capability schema when the context summary is bounded', () => {
    const adapter = fixture();
    vi.mocked(adapter.fieldSpecAliases).mockReturnValue(['string', 'integer', 'custom_41']);
    const add = createMetadataGovernanceAssistantSurface(adapter, vi.fn())
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'configuration.add-metadata-field-draft')!;

    expect(
      (add.descriptor.inputSchema.properties as Record<string, { enum?: string[] }>).fieldSpecAlias?.enum,
    ).toEqual(['string', 'integer', 'custom_41']);
    expect(add.parseInput({ title: '自定义', fieldSpecAlias: 'custom_41' })).toEqual({
      title: '自定义',
      fieldSpecAlias: 'custom_41',
    });
  });
});

function fixture(
  currentProposal: MetadataModelChangeSetProposal = proposal,
): MetadataGovernanceAssistantAdapter {
  return {
    summary: vi.fn(() => ({
      moduleAlias: 'education.exam',
      moduleTitle: '考试管理',
      relationCount: 1,
      selectedRelation: {
        relationId: 'relation-main',
        title: '考试',
        fieldCount: 1,
        fields: [
          {
            fieldName: 'title',
            title: '名称',
            fieldSpecAlias: 'string',
            propertyKind: 'BASIC',
            governance: '业务',
          },
        ],
        truncated: false,
      },
      draft: { active: true, dirty: true, editorOpen: false },
      fieldSpecs: [
        { alias: 'string', title: '短文本' },
        { alias: 'integer', title: '整数' },
      ],
    })),
    proposal: vi.fn(() => currentProposal),
    preview: vi.fn(async () => ({
      proposalFingerprint: 'fingerprint-1',
      fieldImpacts: [
        {
          operation: 'ADD',
          fieldName: 'dailySummary',
          columnName: 'daily_summary',
          platformManaged: false,
          description: '新增日报摘要',
        },
      ],
      schemaImpacts: [
        {
          operation: 'ADD_COLUMN',
          schemaName: 'public',
          tableName: 'exam',
          columnName: 'daily_summary',
          description: '新增日报摘要物理列',
        },
      ],
      orderImpacts: [],
      warnings: [],
      errors: [],
    })),
    fieldSpecAliases: vi.fn(() => ['string', 'integer']),
    editableBasicFieldNames: vi.fn(() => ['title']),
    addFieldDraft: vi.fn(() => ({
      relationId: 'relation-main',
      fieldName: 'examRemark',
      columnName: 'exam_remark',
      title: '考试备注',
      fieldSpecAlias: 'string',
    })),
    updateFieldDraft: vi.fn(() => ({
      relationId: 'relation-main',
      fieldName: 'title',
      title: '考试名称',
      fieldSpecAlias: 'string',
    })),
  };
}

function capability(code: string) {
  return {
    descriptor: { code, description: code, inputSchema: {} },
    parseInput: (input: unknown) => input,
    execute: vi.fn(),
  };
}

function executionContext() {
  return {
    signal: new AbortController().signal,
    isCurrent: () => true,
    commitInternalState<T>(commit: () => T) {
      return commit();
    },
    applyEffect<T>(effect: () => T) {
      applyEffectSpy();
      return effect();
    },
  };
}
