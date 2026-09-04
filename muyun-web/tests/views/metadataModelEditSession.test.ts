import { expect, it } from 'vitest';
import type { MetadataField, ModuleMetadataRelation } from '@/web-contracts';
import {
  createMetadataModelEditSession,
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

it('keeps field and capability changes local until a future change-set facade applies them', () => {
  const session = createMetadataModelEditSession();
  const title: MetadataField = {
    id: 'title',
    version: 2,
    fieldName: 'title',
    title: '标题',
    fieldOwnership: 'BUSINESS',
    fieldForm: 'PHYSICAL',
  };
  session.begin(
    'metadata-1',
    'relation-1',
    3,
    [title],
    [{ capability: 'TREE', enabled: false, selectable: true }],
  );

  session.stageField({ ...title, title: '考试标题' });
  session.stageField({
    fieldName: 'examDate',
    title: '考试日期',
    fieldOwnership: 'BUSINESS',
    fieldForm: 'PHYSICAL',
  });
  session.stageCapability('TREE', true, true);

  expect(session.isDirty.value).toBe(true);
  expect(session.fieldsForDisplay([])).toEqual([
    { ...title, title: '考试标题' },
    { fieldName: 'examDate', title: '考试日期', fieldOwnership: 'BUSINESS', fieldForm: 'PHYSICAL' },
  ]);
  expect(session.draft.value?.capabilitySelections).toEqual({ TREE: true });
  expect(session.buildProposal()).toEqual({
    expectedMetadataVersion: 3,
    capabilitySelections: { TREE: true },
    fieldDrafts: [
      {
        operation: 'UPDATE',
        fieldId: 'title',
        expectedFieldVersion: 2,
        field: { ...title, title: '考试标题' },
      },
      {
        operation: 'ADD',
        field: {
          fieldName: 'examDate',
          title: '考试日期',
          fieldOwnership: 'BUSINESS',
          fieldForm: 'PHYSICAL',
        },
      },
    ],
  });

  session.cancel();
  expect(session.editing.value).toBe(false);
});

it('stages a reference property with its field and carries the binding through the one change set', () => {
  const session = createMetadataModelEditSession();
  session.begin('metadata-1', 'relation-1', 3, [], [], []);

  session.stageField(
    {
      fieldName: 'subjectCategoryId',
      columnName: 'subject_category_id',
      fieldSpecAlias: 'string',
      fieldOwnership: 'BUSINESS',
      fieldForm: 'PHYSICAL',
    },
    {
      kind: 'MODULE_REFERENCE',
      referenceConfig: {
        targetModuleAlias: 'education.subject_category',
        targetKeyField: 'code',
        targetLabelField: 'name',
        cardinality: 'ONE',
        targetUnavailablePolicy: 'RESTRICT',
        projectionMappings: ['name:subjectCategoryIdTitle'],
      },
    },
  );

  const proposal = session.buildProposal();
  expect(proposal?.fieldDrafts).toEqual([
    {
      operation: 'ADD',
      field: {
        fieldName: 'subjectCategoryId',
        columnName: 'subject_category_id',
        fieldSpecAlias: 'string',
        fieldOwnership: 'BUSINESS',
        fieldForm: 'PHYSICAL',
      },
      property: {
        kind: 'MODULE_REFERENCE',
        referenceConfig: {
          targetModuleAlias: 'education.subject_category',
          targetKeyField: 'code',
          targetLabelField: 'name',
          cardinality: 'ONE',
          targetUnavailablePolicy: 'RESTRICT',
          projectionMappings: ['name:subjectCategoryIdTitle'],
        },
      },
    },
  ]);
  expect(JSON.stringify(proposal)).toContain('"projectionMappings":["name:subjectCategoryIdTitle"]');
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
      capabilities: [{ capability: 'TREE', enabled: false, selectable: true }],
    },
    {
      relationId: 'child',
      metadataId: 'metadata-child',
      parentMetadataId: 'metadata-main',
      expectedMetadataVersion: 1,
      fields: [{ id: 'student', fieldName: 'studentId', fieldOwnership: 'BUSINESS' }],
      capabilities: [{ capability: 'TREE', enabled: false, selectable: false }],
    },
  ]);

  session.stageCapability('main', 'TREE', true, true);
  session.stageFieldOrder('main', ['date', 'title']);
  session.stageRelationOrder(undefined, ['main']);

  expect(session.fieldsForDisplay('main', []).slice(0, 2)).toMatchObject([{ id: 'date' }, { id: 'title' }]);
  expect(session.relation('main')?.metadataId).toBe('metadata-main');
  expect(session.buildProposal()).toEqual({
    relationDrafts: [
      {
        relationId: 'main',
        expectedMetadataVersion: 3,
        capabilitySelections: { TREE: true },
        fieldDrafts: [],
      },
    ],
    relationOrders: [],
    fieldOrders: [{ relationId: 'main', fieldIds: ['date', 'title'] }],
  });
});
