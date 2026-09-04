import { assert, it } from 'vitest';
import type { Metadata, MetadataField, ModuleMetadataRelation } from '@/web-contracts/index.ts';
import {
  createMetadataOrchestrationState,
  emptyFieldDraft,
  emptyMainMetadataDraft,
  entityExplorerItem,
  fieldSpecDisplayLabel,
  fieldSpecOptionListOf,
  isValidFieldDraft,
  isValidFieldPropertyDraft,
  isValidMainMetadataDraft,
  dataSafeFieldSpecOptions,
  metadataFieldPropertySummary,
  metadataSubtitleOf,
  normalizeFieldDraft,
  normalizeFieldPropertyDraft,
  normalizeMainMetadataDraft,
  orchestratableFields,
  propertyDraftFromSummary,
  relationRoleTag,
  storageFieldSpecAliasOf,
} from '@/views/metadataOrchestrationState.ts';

function relation(id: string, overrides: Partial<ModuleMetadataRelation> = {}): ModuleMetadataRelation {
  return { id, metadataId: `metadata-${id}`, relationAlias: id, ...overrides };
}

function metadata(id: string, overrides: Partial<Metadata> = {}): Metadata {
  return { id, alias: id, title: `实体${id}`, ...overrides };
}

it('main metadata draft requires alias and title after normalization', () => {
  const draft = emptyMainMetadataDraft();
  assert.equal(isValidMainMetadataDraft(draft), false);

  const filled = normalizeMainMetadataDraft({
    ...draft,
    alias: '  customer  ',
    title: ' 客户 ',
    schemaName: ' public ',
  });
  assert.deepEqual(filled, {
    alias: 'customer',
    title: '客户',
    schemaName: 'public',
    tableName: '',
    dataScopeEnabled: false,
  });
  assert.equal(isValidMainMetadataDraft(filled), true);
});

it('orchestratable fields keep only business-owned physical fields', () => {
  const fields: MetadataField[] = [
    { id: 'f1', fieldName: 'name', fieldOwnership: 'BUSINESS', fieldForm: 'PHYSICAL' },
    { id: 'f2', fieldName: 'tenantId', fieldOwnership: 'PLATFORM', fieldForm: 'PHYSICAL' },
    { id: 'f3', fieldName: 'virtual', fieldOwnership: 'BUSINESS', fieldForm: 'VIRTUAL' },
    {
      id: 'f4',
      fieldName: 'managed',
      fieldOwnership: 'BUSINESS',
      fieldForm: 'PHYSICAL',
      systemManaged: true,
    },
  ];

  assert.deepEqual(
    orchestratableFields(fields).map((field) => field.id),
    ['f1'],
  );
  assert.deepEqual(
    propertyDraftFromSummary({
      fieldId: 'legacy',
      kind: 'LEGACY_LOCKED',
      legacyReason: 'legacy module field',
    }),
    { kind: 'LEGACY_LOCKED' },
  );
});

it('field draft validation and normalization trim identity fields', () => {
  assert.equal(isValidFieldDraft(emptyFieldDraft()), false);

  const draft = normalizeFieldDraft({
    ...emptyFieldDraft(),
    fieldName: '  customerName  ',
    columnName: ' customer_name ',
    title: ' 客户名称 ',
    fieldSpecAlias: 'spec.short_text',
  });
  assert.equal(draft.fieldName, 'customerName');
  assert.equal(draft.columnName, 'customer_name');
  assert.equal(draft.title, '客户名称');
  assert.equal(isValidFieldDraft(draft), true);
});

it('keeps module-reference and dictionary business properties separate from storage specifications', () => {
  const reference = normalizeFieldPropertyDraft({
    kind: 'MODULE_REFERENCE',
    referenceConfig: {
      targetModuleAlias: ' education.subject_category ',
      targetKeyField: ' ',
      targetLabelField: ' ',
      projectionMappings: [' title:subjectCategoryIdTitle ', ''],
    },
  });
  assert.deepEqual(reference, {
    kind: 'MODULE_REFERENCE',
    referenceConfig: {
      targetModuleAlias: 'education.subject_category',
      targetKeyField: 'id',
      targetLabelField: 'title',
      projectionMappings: ['title:subjectCategoryIdTitle'],
    },
  });
  assert.equal(isValidFieldPropertyDraft(reference), true);
  assert.equal(metadataFieldPropertySummary(reference), 'education.subject_category · id → title');

  const dictionary = normalizeFieldPropertyDraft({
    kind: 'DICTIONARY',
    dictionaryConfig: {
      dictionaryApplicationAlias: ' education ',
      dictionaryCategoryAlias: ' exam_attendance_status ',
      selectionMode: 'SINGLE',
    },
  });
  assert.equal(isValidFieldPropertyDraft(dictionary), true);
  assert.equal(metadataFieldPropertySummary(dictionary), 'education · exam_attendance_status');
  assert.equal(storageFieldSpecAliasOf('MODULE_REFERENCE'), 'string');
  assert.equal(storageFieldSpecAliasOf('DICTIONARY', 'SINGLE'), 'string');
  assert.equal(storageFieldSpecAliasOf('DICTIONARY', 'MULTIPLE'), 'json_set');
});

it('adapts relation-scoped property summaries to the change-set property contract', () => {
  assert.deepEqual(
    propertyDraftFromSummary({
      fieldId: 'subject',
      kind: 'MODULE_REFERENCE',
      bindingVersion: 4,
      reference: {
        targetModuleAlias: 'education.subject_category',
        targetKeyField: 'code',
        targetLabelField: 'name',
        cardinality: 'ONE',
        projectionMappings: ['name:subjectCategoryIdTitle'],
      },
    }),
    {
      kind: 'MODULE_REFERENCE',
      expectedBindingVersion: 4,
      referenceConfig: {
        targetModuleAlias: 'education.subject_category',
        targetKeyField: 'code',
        targetLabelField: 'name',
        cardinality: 'ONE',
        projectionMappings: ['name:subjectCategoryIdTitle'],
      },
    },
  );
  assert.deepEqual(
    propertyDraftFromSummary({
      fieldId: 'attendance',
      kind: 'DICTIONARY',
      bindingVersion: 2,
      dictionary: {
        applicationAlias: 'education',
        categoryAlias: 'exam_attendance_status',
        selectionMode: 'SINGLE',
      },
    }),
    {
      kind: 'DICTIONARY',
      expectedBindingVersion: 2,
      dictionaryConfig: {
        dictionaryApplicationAlias: 'education',
        dictionaryCategoryAlias: 'exam_attendance_status',
        selectionMode: 'SINGLE',
      },
    },
  );
});

it('entity explorer item maps title, alias and relation role tag', () => {
  const item = entityExplorerItem(relation('rel-1', { relationRole: 'MAIN' }), metadata('metadata-rel-1'));

  assert.deepEqual(item, {
    title: '实体metadata-rel-1',
    secondary: 'metadata-rel-1',
    tag: '主实体',
    muted: undefined,
  });
  assert.equal(relationRoleTag('CHILD'), '子实体');
  assert.equal(relationRoleTag(undefined), undefined);
});

it('entity explorer item falls back to relation alias and mutes disabled metadata', () => {
  const item = entityExplorerItem(relation('rel-1', { metadataId: undefined }), undefined);
  assert.equal(item.title, 'rel-1');
  assert.equal(item.secondary, undefined);

  const muted = entityExplorerItem(relation('rel-2'), metadata('metadata-rel-2', { enabled: false }));
  assert.equal(muted.muted, true);
});

it('field spec options skip disabled specs and unnamed values', () => {
  const options = fieldSpecOptionListOf([
    { id: 'spec-1', alias: 'spec.short_text', title: '短文本', enabled: true },
    { id: 'spec-2', alias: 'spec.money', title: '金额', enabled: false },
    { title: '无 alias 规格' },
  ]);

  assert.deepEqual(options, [{ value: 'spec.short_text', label: '短文本' }]);
});

it('limits populated entities to lossless field-spec changes', () => {
  const specs = [
    { id: 'string', alias: 'string', title: '短文本', safeTargetFieldSpecAliases: ['text'] },
    { id: 'text', alias: 'text', title: '长文本' },
    { id: 'decimal', alias: 'decimal', title: '小数' },
  ];
  assert.deepEqual(dataSafeFieldSpecOptions(specs, 'string'), [
    { value: 'string', label: '短文本' },
    { value: 'text', label: '长文本' },
  ]);
  assert.deepEqual(dataSafeFieldSpecOptions(specs, 'text'), [{ value: 'text', label: '长文本' }]);
  assert.deepEqual(dataSafeFieldSpecOptions(specs, 'decimal'), [{ value: 'decimal', label: '小数' }]);
});

it('uses the catalog title for a human-facing field specification', () => {
  const specs = [{ id: 'string', alias: 'string', title: '短文本' }];

  assert.equal(fieldSpecDisplayLabel('string', specs), '短文本');
  assert.equal(fieldSpecDisplayLabel('custom.code', specs), 'custom.code');
  assert.equal(fieldSpecDisplayLabel(undefined, specs), '');
});

it('metadata subtitle joins alias and physical table', () => {
  assert.equal(
    metadataSubtitleOf(metadata('m1', { schemaName: 'education', tableName: 'exam' })),
    'm1 · education.exam',
  );
  assert.equal(metadataSubtitleOf(metadata('m1')), 'm1');
  assert.equal(metadataSubtitleOf(undefined), undefined);
});

it('relations reload keeps selection when it still exists', () => {
  const state = createMetadataOrchestrationState();
  state.handleRelationsLoaded([relation('rel-1'), relation('rel-2')]);
  state.selectRelation(relation('rel-2'));

  state.handleRelationsLoaded([relation('rel-1'), relation('rel-2', { relationRole: 'CHILD' })]);

  assert.equal(state.selectedRelationId.value, 'rel-2');
});

it('relations reload falls back to first relation when selection vanished', () => {
  const state = createMetadataOrchestrationState();
  state.handleRelationsLoaded([relation('rel-1'), relation('rel-2')]);
  state.selectRelation(relation('rel-2'));

  state.handleRelationsLoaded([relation('rel-1')]);

  assert.equal(state.selectedRelationId.value, 'rel-1');
});

it('selecting the same relation does not reload details but exits editor modes', () => {
  const state = createMetadataOrchestrationState();
  state.handleRelationsLoaded([relation('rel-1')]);
  state.handleMetadataLoaded(metadata('metadata-rel-1'));
  state.startCreateField();
  assert.equal(state.mode.value, 'create-field');

  assert.equal(state.selectRelation(relation('rel-1')), false);
  assert.equal(state.mode.value, 'view');
});

it('field editor requires a selected metadata and cancels back to it', () => {
  const state = createMetadataOrchestrationState();
  state.handleRelationsLoaded([relation('rel-1')]);

  state.startCreateField();
  assert.equal(state.mode.value, 'view');

  state.handleMetadataLoaded(metadata('metadata-rel-1'));
  state.startCreateField();
  assert.equal(state.mode.value, 'create-field');
  assert.equal(state.fieldEditorOpen.value, true);

  state.cancelEditor();
  assert.equal(state.mode.value, 'view');
  assert.equal(state.selectedRelationId.value, 'rel-1');

  state.startEditField({ id: 'f1', fieldName: 'name' });
  assert.equal(state.mode.value, 'edit-field');
  assert.equal(state.fieldDraft.value.id, 'f1');
});

it('create main metadata editor resets draft on every open', () => {
  const state = createMetadataOrchestrationState();
  state.startCreateMain();
  state.mainMetadataDraft.value = { ...state.mainMetadataDraft.value, alias: 'customer', title: '客户' };

  state.cancelEditor();
  state.startCreateMain();

  assert.equal(state.mainMetadataDraft.value.alias, '');
  assert.equal(state.mainEditorOpen.value, true);
});

it('focus relation moves selection after creating main metadata', () => {
  const state = createMetadataOrchestrationState();
  state.handleRelationsLoaded([relation('rel-1')]);
  state.startCreateMain();

  state.focusRelation('rel-2');

  assert.equal(state.selectedRelationId.value, 'rel-2');
  assert.equal(state.mode.value, 'view');
});
