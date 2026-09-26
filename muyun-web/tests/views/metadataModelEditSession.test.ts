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

it('replaces an unsaved field when a failed preview is corrected with a new technical name', () => {
  const session = createMetadataModelWorkspaceEditSession();
  session.begin([
    { relationId: 'main', metadataId: 'metadata-main', expectedMetadataVersion: 1, fields: [] },
  ]);

  session.stageField('main', { fieldName: 'taskName', columnName: 'task_name', fieldSpecAlias: 'string' });
  session.stageField(
    'main',
    { fieldName: 'title', columnName: 'title', fieldSpecAlias: 'string' },
    undefined,
    'taskName',
  );

  expect(session.fieldsForDisplay('main', [])).toMatchObject([{ fieldName: 'title', columnName: 'title' }]);
  expect(session.buildProposal()?.relationDrafts[0]?.fieldDrafts).toMatchObject([
    { operation: 'ADD', field: { fieldName: 'title', columnName: 'title' } },
  ]);
});

it('writes dictionary selection cardinality with its CodeTitleEnum wire code', () => {
  const session = createMetadataModelWorkspaceEditSession();
  session.begin([
    { relationId: 'main', metadataId: 'metadata-main', expectedMetadataVersion: 1, fields: [] },
  ]);

  session.stageField(
    'main',
    { fieldName: 'attendanceStatus', columnName: 'attendance_status', fieldSpecAlias: 'string' },
    {
      kind: 'DICTIONARY',
      dictionaryConfig: {
        dictionaryApplicationAlias: 'education',
        dictionaryCategoryAlias: 'exam_attendance_status',
        selectionMode: 'SINGLE',
      },
    },
  );

  expect(session.buildProposal()?.relationDrafts[0]?.fieldDrafts[0]?.property).toMatchObject({
    kind: 'DICTIONARY',
    dictionaryConfig: {
      dictionaryApplicationAlias: 'education',
      dictionaryCategoryAlias: 'exam_attendance_status',
      selectionMode: 'single',
    },
  });
});

it('projects a renamed visible candidate without mutating the staged session or baseline', () => {
  const session = createMetadataModelWorkspaceEditSession();
  session.begin([{ relationId: 'r', metadataId: 'm', expectedMetadataVersion: 3, fields: [] }]);
  session.stageField('r', { fieldName: 'oldName', title: '原候选' }, { kind: 'BASIC' });
  const before = session.buildProposal();
  const preview = session.proposalWithField(
    'r',
    { fieldName: 'newName', title: '手工修改' },
    { kind: 'BASIC' },
    'oldName',
  );
  expect(preview?.relationDrafts[0]?.fieldDrafts).toEqual([
    expect.objectContaining({ operation: 'ADD', field: expect.objectContaining({ fieldName: 'newName' }) }),
  ]);
  expect(preview?.relationDrafts[0]?.expectedMetadataVersion).toBe(3);
  expect(session.buildProposal()).toEqual(before);
  expect(session.fieldsForDisplay('r', []).map((field) => field.fieldName)).toEqual(['oldName']);
});

it('stages a complete field plan atomically and removes only unsaved additions', () => {
  const session = createMetadataModelWorkspaceEditSession();
  session.begin([
    {
      relationId: 'main',
      metadataId: 'meta',
      expectedMetadataVersion: 3,
      fields: [{ id: 'persisted', fieldName: 'title', version: 2 }],
    },
  ]);
  expect(() =>
    session.stageFields('main', [
      { field: { fieldName: 'first' }, property: { kind: 'BASIC' } },
      { field: { fieldName: 'Title' }, property: { kind: 'BASIC' } },
    ]),
  ).toThrow('Duplicate');
  expect(session.isDirty.value).toBe(false);
  session.stageFields('main', [
    { field: { fieldName: 'first', title: '第一项' }, property: { kind: 'BASIC' } },
    {
      field: { fieldName: 'second' },
      property: {
        kind: 'DICTIONARY',
        dictionaryConfig: {
          dictionaryApplicationAlias: 'crm',
          dictionaryCategoryAlias: 'status',
          selectionMode: 'SINGLE',
        },
      },
    },
  ]);
  session.discardNewField('main', 'persisted');
  session.discardNewField('main', 'first');
  expect(session.fieldsForDisplay('main', []).map((field) => field.fieldName)).toEqual(['title', 'second']);
  expect(session.buildProposal()?.relationDrafts[0]).toMatchObject({
    expectedMetadataVersion: 3,
    fieldDrafts: [
      {
        operation: 'ADD',
        field: { fieldName: 'second' },
        property: { dictionaryConfig: { selectionMode: 'single' } },
      },
    ],
  });
  session.discardNewField('main', 'second');
  expect(session.buildProposal()?.relationDrafts).toEqual([]);
});
