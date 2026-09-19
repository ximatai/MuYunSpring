import { describe, expect, it, vi } from 'vitest';
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

describe('metadata governance assistant surface', () => {
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
    ).toEqual(['configuration.describe-metadata-model']);

    const current = fixture();
    const preview = createMetadataGovernanceAssistantSurface(current, vi.fn())
      .capabilities()
      .find(({ descriptor }) => descriptor.code === 'configuration.preview-metadata-draft')!;
    await expect(
      preview.execute(preview.parseInput({}), { ...executionContext(), isCurrent: () => false }),
    ).rejects.toThrow('Metadata candidate preview is no longer current');
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
            governance: '业务',
          },
        ],
        truncated: false,
      },
      draft: { active: true, dirty: true },
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
      return effect();
    },
  };
}
