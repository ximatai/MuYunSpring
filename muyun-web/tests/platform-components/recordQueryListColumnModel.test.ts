import { expect, it } from 'vitest';
import { resolveRecordQueryListColumns } from '@/platform-components/recordQueryListColumnModel.ts';
import type { QuerySchemaField, ResolvedViewDescriptor } from '@/web-contracts/index.ts';

it('maps visible resolved list fields into the standard list presentation contract', () => {
  const columns = resolveRecordQueryListColumns(
    listView([
      {
        fieldRef: { fieldName: 'name' },
        label: '名称',
        width: '240px',
        align: 'center',
        maxDisplayLines: 2,
      },
      { fieldRef: { fieldName: 'enabled' }, uiType: 'enabledStatus' },
      {
        fieldRef: { fieldName: 'published' },
        uiType: 'booleanStatus',
        booleanStatus: { trueLabel: '已发布', falseLabel: '未发布', trueTone: 'SUCCESS' },
      },
      { fieldRef: { fieldName: 'tags' }, uiType: 'tagList' },
      {
        fieldRef: { fieldName: 'color' },
        fieldControl: { alias: 'colorPicker', rendererType: 'COLOR_PICKER', valueShape: 'SCALAR' },
      },
      { fieldRef: { fieldName: 'size' }, valuePresentation: 'FILE_SIZE' },
      { fieldRef: { fieldName: 'updatedAt' }, valueType: 'ZONED_TIMESTAMP', align: 'unexpected' },
      { fieldRef: { fieldName: 'internal' }, visible: { constant: false } },
    ]),
  );

  expect(columns).toEqual([
    {
      key: 'name',
      title: '名称',
      type: 'text',
      width: '240px',
      align: 'center',
      titleField: undefined,
      booleanStatus: undefined,
      maxDisplayLines: 2,
    },
    {
      key: 'enabled',
      title: 'enabled',
      type: 'enabledStatus',
      width: undefined,
      align: 'left',
      titleField: undefined,
      booleanStatus: undefined,
      maxDisplayLines: undefined,
    },
    {
      key: 'published',
      title: 'published',
      type: 'booleanStatus',
      width: undefined,
      align: 'left',
      titleField: undefined,
      booleanStatus: { trueLabel: '已发布', falseLabel: '未发布', trueTone: 'SUCCESS' },
      maxDisplayLines: undefined,
    },
    {
      key: 'tags',
      title: 'tags',
      type: 'tagList',
      width: undefined,
      align: 'left',
      titleField: undefined,
      booleanStatus: undefined,
      maxDisplayLines: undefined,
    },
    {
      key: 'color',
      title: 'color',
      type: 'colorPicker',
      width: undefined,
      align: 'left',
      titleField: undefined,
      booleanStatus: undefined,
      maxDisplayLines: undefined,
    },
    {
      key: 'size',
      title: 'size',
      type: 'fileSize',
      width: undefined,
      align: 'left',
      titleField: undefined,
      booleanStatus: undefined,
      maxDisplayLines: undefined,
    },
    {
      key: 'updatedAt',
      title: 'updatedAt',
      type: 'datetime',
      width: undefined,
      align: 'left',
      titleField: undefined,
      booleanStatus: undefined,
      maxDisplayLines: undefined,
    },
  ]);
});

it('retains query-schema fallbacks for legacy descriptors without changing descriptor precedence', () => {
  const queryFields: QuerySchemaField[] = [
    {
      name: 'createdAt',
      valueType: 'INSTANT',
      operators: [],
      optionTitleField: 'createdAtTitle',
    },
    {
      name: 'ownerId',
      valueType: 'STRING',
      operators: [],
      optionTitleField: 'ownerName',
    },
  ];

  expect(
    resolveRecordQueryListColumns(
      listView([
        { fieldRef: { fieldName: 'createdAt' } },
        {
          fieldRef: { fieldName: 'ownerId' },
          option: {
            binding: { sourceType: 'OPTION', source: 'owner' },
            selectionMode: 'SINGLE',
            titleField: 'descriptorOwnerTitle',
          },
        },
      ]),
      queryFields,
    ),
  ).toMatchObject([
    { key: 'createdAt', type: 'datetime', titleField: 'createdAtTitle' },
    { key: 'ownerId', type: 'text', titleField: 'descriptorOwnerTitle' },
  ]);
});

it('returns no columns when a list descriptor is unavailable', () => {
  expect(resolveRecordQueryListColumns(undefined)).toEqual([]);
});

it('uses the declared reference convention when a list descriptor has no option binding', () => {
  expect(
    resolveRecordQueryListColumns(
      listView([
        {
          fieldRef: { fieldName: 'classroomId' },
          reference: { targetModuleAlias: 'education.classroom', cardinality: 'ONE' },
        },
      ]),
    ),
  ).toMatchObject([{ key: 'classroomId', titleField: 'classroomIdTitle' }]);
});

function listView(fields: ResolvedViewDescriptor['fields']): ResolvedViewDescriptor {
  return { viewCode: 'list', viewKind: 'LIST', fields };
}
