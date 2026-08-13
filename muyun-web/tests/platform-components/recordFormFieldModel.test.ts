import { assert, it } from 'vitest';
import {
  childResourceDefaultFormViewCode,
  resolveRecordFormFields,
  resolveRecordFormGroups,
  resolveRecordBooleanStatusValue,
  resolveRecordFormFieldNames,
  resolveRecordFormFieldState,
  type RecordFormFieldDescriptor,
  type RecordFormFieldFallback,
} from '@/platform-components/recordFormFieldModel.ts';
import {
  hasOptionHierarchy,
  optionItemsToOptions,
  optionItemsToTree,
} from '@/platform-components/optionFieldOptions.ts';
import type { ResolvedModuleUiDescriptor } from '@/web-contracts/index.ts';

it('record form field names prefer descriptor order and fill missing fallback fields', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    ['organizationId', field('所属机构')],
    ['title', field('名称')],
  ]);
  const fallback: Record<string, RecordFormFieldFallback> = {
    organizationId: { label: '所属机构' },
    title: { label: '名称' },
    enabled: { label: '启用状态', controlType: 'enabledStatus' },
  };

  assert.deepEqual(resolveRecordFormFieldNames(fields, fallback, { exclude: ['organizationId'] }), [
    'title',
    'enabled',
  ]);
});

it('record form field names use fallback order when descriptor is missing', () => {
  const fallback: Record<string, RecordFormFieldFallback> = {
    parentId: { label: '上级', controlType: 'recordPicker' },
    code: { label: '编码' },
    enabled: { label: '启用状态', controlType: 'enabledStatus' },
  };

  assert.deepEqual(resolveRecordFormFieldNames(undefined, fallback), ['parentId', 'code', 'enabled']);
});

it('record form field state resolves descriptor facts with fallback control metadata', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    ['title', field('显示名称', { required: true })],
  ]);
  const fallback: Record<string, RecordFormFieldFallback> = {
    title: { label: '名称', placeholder: '请输入名称' },
    parentId: { label: '上级', controlType: 'recordPicker', placeholder: '根节点留空' },
  };

  assert.deepEqual(resolveRecordFormFieldState('title', { fields, fallback }), {
    fieldName: 'title',
    label: '显示名称',
    required: true,
    readOnly: false,
    visible: true,
    controlType: 'input',
    columnSpan: 1,
    hasOption: false,
    pickerConfig: undefined,
    placeholder: '请输入名称',
  });
  assert.equal(resolveRecordFormFieldState('parentId', { fallback }).controlType, 'recordPicker');
});

it('record form field state resolves select options from fallback metadata', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    ['categoryKind', field('类目类型', { required: true, uiType: 'select' })],
  ]);
  const fallback: Record<string, RecordFormFieldFallback> = {
    categoryKind: {
      label: '类目类型',
      controlType: 'select',
      options: [
        { label: '字典', value: 'DICTIONARY' },
        { label: '目录', value: 'FOLDER' },
      ],
    },
  };

  assert.deepEqual(resolveRecordFormFieldState('categoryKind', { fields, fallback }), {
    fieldName: 'categoryKind',
    label: '类目类型',
    required: true,
    readOnly: false,
    visible: true,
    controlType: 'select',
    columnSpan: 1,
    hasOption: false,
    pickerConfig: undefined,
    placeholder: undefined,
    options: [
      { label: '字典', value: 'DICTIONARY' },
      { label: '目录', value: 'FOLDER' },
    ],
  });
});

it('record form groups preserve fields nested by the UI descriptor and attach them to rendered fields', () => {
  const uiDescriptor = {
    schemaVersion: '1',
    moduleAlias: 'iam.tenant',
    views: [
      {
        viewCode: 'default_form',
        viewKind: 'FORM',
        fields: [
          {
            fieldRef: { fieldName: 'workbenchTitle' },
            label: '主标题',
          },
        ],
        formGroups: [
          {
            groupCode: 'workbench_branding',
            title: '主标题UI个性化配置',
            subtitle: '控制工作台标题和 Logo。',
            fields: [{ fieldName: 'workbenchTitle' }],
          },
        ],
      },
    ],
  } satisfies ResolvedModuleUiDescriptor;
  const groups = resolveRecordFormGroups(uiDescriptor);

  assert.deepEqual(groups, [
    {
      groupCode: 'workbench_branding',
      title: '主标题UI个性化配置',
      subtitle: '控制工作台标题和 Logo。',
      fields: [{ fieldName: 'workbenchTitle' }],
    },
  ]);
  assert.equal(
    resolveRecordFormFields(uiDescriptor).get('workbenchTitle')?.formGroup?.groupCode,
    'workbench_branding',
  );
});

it('record form field state preserves a descriptor switch as a generic boolean control', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    ['completed', field('已完成', { uiType: 'switch' })],
  ]);

  assert.equal(resolveRecordFormFieldState('completed', { fields }).controlType, 'switch');
});

it('business boolean status preserves false and unknown values instead of defaulting to enabled', () => {
  assert.equal(resolveRecordBooleanStatusValue(true), true);
  assert.equal(resolveRecordBooleanStatusValue(false), false);
  assert.equal(resolveRecordBooleanStatusValue(null), undefined);
  assert.equal(resolveRecordBooleanStatusValue(undefined), undefined);
  assert.equal(resolveRecordBooleanStatusValue('true'), undefined);
});

it('record form field state renders a textarea descriptor as a text area', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    ['remark', field('备注', { uiType: 'textarea' })],
  ]);

  assert.equal(resolveRecordFormFieldState('remark', { fields }).controlType, 'textarea');
});

it('record form field state renders a color picker descriptor with the shared color control', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    ['color', field('颜色', { uiType: 'colorPicker' })],
  ]);

  assert.equal(resolveRecordFormFieldState('color', { fields }).controlType, 'colorPicker');
});

it('record form field state uses the single-image field for one image file reference', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'logoAssetId',
      {
        ...field('Logo'),
        fileReference: { maxFiles: 1, allowedMediaTypes: ['image/png', 'image/webp'] },
      } as RecordFormFieldDescriptor,
    ],
  ]);

  assert.equal(resolveRecordFormFieldState('logoAssetId', { fields }).controlType, 'imageFileTransfer');
});

it('record form field state preserves typed file size presentation for read-only details', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    ['fileSize', { ...field('文件大小', { readOnly: true }), valuePresentation: 'FILE_SIZE' }],
  ]);

  assert.equal(resolveRecordFormFieldState('fileSize', { fields }).valuePresentation, 'FILE_SIZE');
});

it('record form field state infers reference picker cardinality without a UI override', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'organizationId',
      { ...field('所属机构'), reference: { targetModuleAlias: 'iam.organization', cardinality: 'ONE' } },
    ],
    [
      'tagIds',
      { ...field('标签'), uiType: 'text', reference: { targetModuleAlias: 'crm.tag', cardinality: 'MANY' } },
    ],
  ]);

  assert.equal(resolveRecordFormFieldState('organizationId', { fields }).controlType, 'recordPicker');
  assert.equal(resolveRecordFormFieldState('tagIds', { fields }).controlType, 'recordMultiPicker');
});

it('record form field state exposes a full-row layout span from its descriptor', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    ['remark', { ...field('备注', { uiType: 'textarea' }), columnSpan: 2 }],
  ]);

  assert.equal(resolveRecordFormFieldState('remark', { fields }).columnSpan, 2);
});

it('record form field state makes resolved option fields into selects without page fallback metadata', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'gender',
      {
        ...field('性别'),
        option: {
          binding: { sourceType: 'dictionary', source: 'iam.gender' },
          selectionMode: 'SINGLE',
          titleField: 'genderTitle',
        },
      },
    ],
  ]);

  assert.deepEqual(resolveRecordFormFieldState('gender', { fields }), {
    fieldName: 'gender',
    label: '性别',
    required: false,
    readOnly: false,
    visible: true,
    controlType: 'select',
    columnSpan: 1,
    hasOption: true,
    optionSelectionMode: 'SINGLE',
    optionTitleField: 'genderTitle',
    pickerConfig: undefined,
    placeholder: undefined,
  });
});

it('option items preserve tree hierarchy while exposing flat select options', () => {
  const items = [
    { code: 'root', title: '根节点', enabled: true, sortOrder: 10 },
    { code: 'child', title: '子节点', enabled: true, sortOrder: 20, parentCode: 'root' },
  ];

  assert.equal(hasOptionHierarchy(items), true);
  assert.deepEqual(optionItemsToOptions(items), [
    { label: '根节点', value: 'root', disabled: false },
    { label: '子节点', value: 'child', disabled: false },
  ]);
  assert.deepEqual(optionItemsToTree(items), [
    {
      value: 'root',
      title: '根节点',
      disabled: false,
      children: [{ value: 'child', title: '子节点', disabled: false, children: [] }],
    },
  ]);
});

it('option items retain disabled historical values for editing without making them selectable', () => {
  assert.deepEqual(optionItemsToOptions([{ code: 'legacy', title: '历史值', enabled: false }]), [
    { label: '历史值', value: 'legacy', disabled: true },
  ]);
});

it('record form fields resolve form view descriptors by view code', () => {
  const uiDescriptor = {
    schemaVersion: '1',
    moduleAlias: 'platform.dictionary_category',
    views: [
      {
        viewCode: 'default_list',
        viewKind: 'LIST',
        fields: [descriptorField('title', '列表标题')],
      },
      {
        viewCode: 'default_form',
        viewKind: 'FORM',
        fields: [descriptorField('alias', '类目 alias'), descriptorField('title', '类目名称')],
      },
      {
        viewCode: 'item_default_form',
        viewKind: 'FORM',
        fields: [descriptorField('code', '字典项编码'), descriptorField('parentId', '上级字典项')],
      },
    ],
  } satisfies ResolvedModuleUiDescriptor;

  assert.deepEqual([...resolveRecordFormFields(uiDescriptor).keys()], ['alias', 'title']);
  assert.deepEqual(
    [...resolveRecordFormFields(uiDescriptor, childResourceDefaultFormViewCode('item')).keys()],
    ['code', 'parentId'],
  );
  assert.deepEqual([...resolveRecordFormFields(uiDescriptor, 'missing_form').keys()], []);
  assert.deepEqual([...resolveRecordFormFields(undefined).keys()], []);
});

it('record form fields attach declared file-reference constraints and infer the transfer control', () => {
  const uiDescriptor = {
    schemaVersion: '1',
    moduleAlias: 'mr.knowledge_file',
    fileReferences: [
      {
        fieldRef: { fieldName: 'fileId' },
        allowedMediaTypes: ['application/pdf'],
        maxFileSizeBytes: 1024,
        maxFiles: 1,
        storagePolicy: 'MUYUN_FILE_SERVER',
        uploadAvailable: true,
        readAvailable: true,
      },
    ],
    views: [
      {
        viewCode: 'default_form',
        viewKind: 'FORM',
        fields: [descriptorField('fileId', '上传文件')],
      },
    ],
  } satisfies ResolvedModuleUiDescriptor;

  const fields = resolveRecordFormFields(uiDescriptor);
  assert.deepEqual(fields.get('fileId')?.fileReference, uiDescriptor.fileReferences[0]);
  assert.equal(resolveRecordFormFieldState('fileId', { fields }).controlType, 'fileTransfer');
});

it('child resource default form view code follows platform naming rules', () => {
  assert.equal(childResourceDefaultFormViewCode('item'), 'item_default_form');
  assert.equal(childResourceDefaultFormViewCode('position'), 'position_default_form');
  assert.throws(() => childResourceDefaultFormViewCode('Position'), /invalid child resource code/);
  assert.throws(() => childResourceDefaultFormViewCode(''), /invalid child resource code/);
});

it('record form field state evaluates platform Boolean formulas against the current draft', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'fileId',
      {
        ...field('文件标识'),
        readOnly: {
          formula: { expression: '!(PRESENT({directoryId}))' },
        },
      },
    ],
  ]);

  assert.equal(resolveRecordFormFieldState('fileId', { fields, record: {} }).readOnly, true);
  assert.equal(
    resolveRecordFormFieldState('fileId', { fields, record: { directoryId: 'directory-1' } }).readOnly,
    false,
  );
});

it('record form field state evaluates portable formula conjunctions for create-only editors', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'fileId',
      {
        ...field('文件标识'),
        readOnly: { formula: { expression: '!(PRESENT({directoryId}) && !(PRESENT({id})))' } },
      },
    ],
  ]);

  assert.equal(resolveRecordFormFieldState('fileId', { fields, record: {} }).readOnly, true);
  assert.equal(
    resolveRecordFormFieldState('fileId', { fields, record: { directoryId: 'directory-1' } }).readOnly,
    false,
  );
  assert.equal(
    resolveRecordFormFieldState('fileId', { fields, record: { directoryId: 'directory-1', id: 'file-1' } })
      .readOnly,
    true,
  );
});

it('record form field state retains a fallback disabled hint when its descriptor has none', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'fileId',
      {
        ...field('文件标识'),
        readOnly: { formula: { expression: '!(PRESENT({directoryId}))' } },
      },
    ],
  ]);

  assert.equal(
    resolveRecordFormFieldState('fileId', {
      fields,
      fallback: { fileId: { label: '上传文件', disabledHint: '请先选择归属目录' } },
      record: {},
    }).disabledHint,
    '请先选择归属目录',
  );
});

function field(
  label: string,
  options: { required?: boolean; readOnly?: boolean; visible?: boolean; uiType?: string } = {},
): RecordFormFieldDescriptor {
  return {
    label,
    uiType: options.uiType,
    required: { constant: options.required ?? false },
    readOnly: { constant: options.readOnly ?? false },
    visible: { constant: options.visible ?? true },
  } as RecordFormFieldDescriptor;
}

function descriptorField(fieldName: string, label: string): RecordFormFieldDescriptor {
  return {
    fieldRef: { fieldName },
    label,
    required: { constant: false },
    readOnly: { constant: false },
    visible: { constant: true },
  } as RecordFormFieldDescriptor;
}
