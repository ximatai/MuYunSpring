import { expect, it } from 'vitest';
import type { MetadataField, ModuleMetadataRelation } from '@/web-contracts';
import {
  createMetadataModelWorkspaceEditSession,
  isSessionEditableMetadataField,
  metadataFieldGovernanceKind,
  metadataFieldGovernanceLabel,
} from '@/views/metadataModelEditSession.ts';

const relation: ModuleMetadataRelation = { id: 'relation-1', relationRole: 'MAIN', foreignKey: 'examId' };
const capabilityFields = new Set(['parentId']);

it('classifies field governance ownership before an edit session exposes operations', () => {
  const business: MetadataField = {
    id: 'title',
    version: 2,
    fieldName: 'title',
    fieldOwnership: 'BUSINESS',
    fieldForm: 'PHYSICAL',
  };
  const capability: MetadataField = {
    id: 'parent',
    fieldName: 'parentId',
    fieldOwnership: 'STANDARD',
    systemManaged: true,
  };
  const system: MetadataField = {
    id: 'system:id',
    fieldName: 'id',
    fieldOwnership: 'PLATFORM',
    systemManaged: true,
  };
  const foreignKey: MetadataField = {
    id: 'exam',
    fieldName: 'examId',
    fieldOwnership: 'BUSINESS',
    fieldForm: 'PHYSICAL',
  };

  expect(metadataFieldGovernanceKind(business, relation, capabilityFields)).toBe('BUSINESS');
  expect(metadataFieldGovernanceKind(capability, relation, capabilityFields)).toBe('CAPABILITY_DERIVED');
  expect(metadataFieldGovernanceKind(system, relation, capabilityFields)).toBe('PLATFORM_SYSTEM');
  expect(metadataFieldGovernanceKind(foreignKey, relation, capabilityFields)).toBe('RELATION_FOREIGN_KEY');
  expect(metadataFieldGovernanceLabel('BUSINESS')).toBe('业务');
  expect(metadataFieldGovernanceLabel('PLATFORM_SYSTEM')).toBe('平台');
  expect(metadataFieldGovernanceLabel('RELATION_FOREIGN_KEY')).toBe('关系');
  expect(isSessionEditableMetadataField(business, relation, capabilityFields)).toBe(true);
  expect(isSessionEditableMetadataField(foreignKey, relation, capabilityFields)).toBe(false);
});

it('keeps relation and field ordering with every node draft until one module proposal is built', () => {
  const session = createMetadataModelWorkspaceEditSession();
  session.begin([
    {
      relationId: 'main',
      metadataId: 'metadata-main',
      expectedMetadataVersion: 3,
      fields: [
        { id: 'title', version: 2, fieldName: 'title', fieldOwnership: 'BUSINESS' },
        { id: 'date', version: 2, fieldName: 'examDate', fieldOwnership: 'BUSINESS' },
        { id: 'tenant', version: 2, fieldName: 'tenantId', fieldOwnership: 'PLATFORM' },
      ],
      sortableFieldIds: ['title', 'date'],
    },
    {
      relationId: 'child',
      metadataId: 'metadata-child',
      parentMetadataId: 'metadata-main',
      expectedMetadataVersion: 1,
      fields: [{ id: 'student', fieldName: 'studentId', fieldOwnership: 'BUSINESS' }],
    },
  ]);

  session.stageFieldOrder('main', ['date', 'title']);
  session.stageRelationOrder(undefined, ['main']);

  expect(session.fieldsForDisplay('main', []).slice(0, 2)).toMatchObject([{ id: 'date' }, { id: 'title' }]);
  expect(session.relation('main')?.metadataId).toBe('metadata-main');
  expect(session.buildProposal()).toEqual({
    relationDrafts: [],
    relationOrders: [],
    fieldOrders: [{ relationId: 'main', fieldIds: ['date', 'title'] }],
  });
});
