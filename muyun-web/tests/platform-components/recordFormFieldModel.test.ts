import { assert, expect, it } from 'vitest';
import {
  childResourceDefaultFormViewCode,
  resolveRecordDetailFields,
  resolveRecordFormFields,
  resolveRecordFormGroups,
  resolveRecordBooleanStatusValue,
  resolveRecordFormFieldNames,
  resolveRecordFormFieldState,
  evaluateUiFormula,
  recordFieldRendererRegistry,
  decodeNumberEditorValue,
  type RecordFormFieldDescriptor,
  type RecordFormFieldFallback,
} from '@/platform-components/recordFormFieldModel.ts';
import {
  hasOptionHierarchy,
  optionItemsToOptions,
  optionItemsToTree,
} from '@/platform-components/optionFieldOptions.ts';
import type { ResolvedModuleUiDescriptor } from '@/web-contracts/index.ts';

it('registers every renderer kind promised by the persisted web-form support matrix', () => {
  const rendererTypes = new Set(recordFieldRendererRegistry.map((renderer) => renderer.rendererType));
  // Keep this aligned with FieldUiControlPresetCatalog.WEB_FORM_EXECUTABLE_RENDERERS.  The
  // backend rejects any configured renderer outside that matrix before descriptor publication.
  expect([...rendererTypes]).toEqual(
    expect.arrayContaining([
      'TEXT',
      'TEXTAREA',
      'NUMBER',
      'DECIMAL',
      'SWITCH',
      'SELECT',
      'MULTI_SELECT',
      'DATE',
      'DATETIME',
      'JSON',
    ]),
  );
});

it('keeps LONG and DECIMAL editor transport lossless while INTEGER remains a JSON number', () => {
  expect(decodeNumberEditorValue('42', 'INTEGER')).toBe(42);
  expect(decodeNumberEditorValue('9007199254740993', 'LONG')).toBe('9007199254740993');
  expect(decodeNumberEditorValue('9999999999999999.99', 'DECIMAL')).toBe('9999999999999999.99');
  expect(decodeNumberEditorValue('0.123456789012345678', 'DECIMAL')).toBe('0.123456789012345678');
  expect(decodeNumberEditorValue('1e6', 'DECIMAL')).toBeUndefined();
});

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
    page: {
      template: 'LIST_DETAIL_CARD',
      list: { searchPlaceholder: '', fields: { viewCode: 'page_list', viewKind: 'LIST', fields: [] } },
      detail: {
        emptyDescription: '',
        createTitle: '',
        editor: {
          viewCode: 'page_detail_editor',
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
      },
      traits: [],
    },
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

it('resolved field controls take precedence over legacy uiType and use the registered renderer', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'categoryCodes',
      {
        ...field('分类', { uiType: 'text' }),
        fieldControl: { alias: 'multi_select', rendererType: 'MULTI_SELECT', valueShape: 'COLLECTION' },
        option: {
          binding: { sourceType: 'dictionary', source: 'crm.category' },
          selectionMode: 'MULTIPLE',
        },
      },
    ],
  ]);

  const state = resolveRecordFormFieldState('categoryCodes', { fields });
  assert.equal(state.controlType, 'select');
  assert.equal(state.optionSelectionMode, 'MULTIPLE');
});

it('refuses a multi-select descriptor without its option binding instead of degrading collection transport to input', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'categoryCodes',
      {
        ...field('分类', { uiType: 'text' }),
        fieldControl: { alias: 'multi_select', rendererType: 'MULTI_SELECT', valueShape: 'COLLECTION' },
      },
    ],
  ]);

  const state = resolveRecordFormFieldState('categoryCodes', { fields });
  assert.equal(state.controlType, 'unsupported');
  assert.match(state.rendererDiagnostic ?? '', /multi_select/);
});

it('refuses a select descriptor without its option binding instead of degrading enum transport to input', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'status',
      {
        ...field('状态', { uiType: 'text' }),
        fieldControl: { alias: 'select', rendererType: 'SELECT', valueShape: 'SCALAR' },
      },
    ],
  ]);

  const state = resolveRecordFormFieldState('status', { fields });
  assert.equal(state.controlType, 'unsupported');
  assert.match(state.rendererDiagnostic ?? '', /select/);
});

it('an unknown resolved field-control renderer refuses editing instead of falling back to input', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'range',
      {
        ...field('区间', { uiType: 'text' }),
        fieldControl: {
          alias: 'range',
          rendererType: 'RANGE',
          valueShape: 'COMPOSITE',
          bindings: [
            { key: 'start', valueType: 'DATE' },
            { key: 'end', valueType: 'DATE' },
          ],
        },
      },
    ],
  ]);

  const state = resolveRecordFormFieldState('range', { fields });
  assert.equal(state.controlType, 'unsupported');
  assert.match(state.rendererDiagnostic ?? '', /range/);
});

it.each(['number', 'integer', 'amount', 'percentage'])(
  'record form field state renders the %s descriptor as a numeric input',
  (uiType) => {
    const fields = new Map<string, RecordFormFieldDescriptor>([['quantity', field('数量', { uiType })]]);

    assert.equal(resolveRecordFormFieldState('quantity', { fields }).controlType, 'numberInput');
  },
);

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

it('record form field state preserves descriptor-inline enum items for display without a runtime request', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'moduleKind',
      {
        ...field('模块类型'),
        option: {
          binding: { sourceType: 'enum', source: 'example.ModuleKind' },
          selectionMode: 'SINGLE',
          inlineItems: [{ code: 'static', title: '静态模块', enabled: true }],
        },
      },
    ],
  ]);

  assert.deepEqual(resolveRecordFormFieldState('moduleKind', { fields }).optionItems, [
    { code: 'static', title: '静态模块', enabled: true },
  ]);
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
    page: {
      template: 'LIST_DETAIL_CARD',
      list: {
        searchPlaceholder: '',
        fields: { viewCode: 'page_list', viewKind: 'LIST', fields: [descriptorField('title', '列表标题')] },
      },
      detail: {
        emptyDescription: '',
        createTitle: '',
        editor: {
          viewCode: 'page_detail_editor',
          viewKind: 'FORM',
          fields: [descriptorField('alias', '类目 alias'), descriptorField('title', '类目名称')],
        },
      },
      traits: [],
    },
    editorContributions: [
      {
        resource: 'item',
        editor: {
          viewCode: 'item_editor',
          viewKind: 'FORM',
          fields: [descriptorField('code', '字典项编码'), descriptorField('parentId', '上级字典项')],
        },
      },
    ],
    editorSurfaces: [
      {
        key: 'quick_rename',
        editor: {
          viewCode: 'quick_rename_editor',
          viewKind: 'FORM',
          fields: [descriptorField('title', '名称')],
        },
      },
    ],
  } satisfies ResolvedModuleUiDescriptor;

  assert.deepEqual([...resolveRecordFormFields(uiDescriptor).keys()], ['alias', 'title']);
  assert.deepEqual([...resolveRecordFormFields(uiDescriptor, 'item').keys()], ['code', 'parentId']);
  assert.deepEqual([...resolveRecordFormFields(uiDescriptor, undefined, 'quick_rename').keys()], ['title']);
  assert.deepEqual([...resolveRecordFormFields(uiDescriptor, 'missing_form').keys()], []);
  assert.deepEqual([...resolveRecordFormFields(undefined).keys()], []);
});

it('record detail fields prefer the declared display projection over the editor', () => {
  const uiDescriptor = {
    schemaVersion: '1',
    moduleAlias: 'platform.module',
    page: {
      template: 'TREE_MANAGEMENT',
      detail: {
        emptyDescription: '',
        createTitle: '',
        display: {
          viewCode: 'page_detail_display',
          viewKind: 'FORM',
          fields: [descriptorField('applicationAlias', '所属应用'), descriptorField('alias', '模块 alias')],
        },
        editor: {
          viewCode: 'page_detail_editor',
          viewKind: 'FORM',
          fields: [descriptorField('alias', '模块 alias'), descriptorField('title', '模块名称')],
        },
      },
      traits: [],
    },
  } satisfies ResolvedModuleUiDescriptor;

  assert.deepEqual([...resolveRecordDetailFields(uiDescriptor).keys()], ['applicationAlias', 'alias']);
  assert.deepEqual([...resolveRecordFormFields(uiDescriptor).keys()], ['alias', 'title']);
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
    page: {
      template: 'LIST_DETAIL_CARD',
      list: { searchPlaceholder: '', fields: { viewCode: 'page_list', viewKind: 'LIST', fields: [] } },
      detail: {
        emptyDescription: '',
        createTitle: '',
        editor: {
          viewCode: 'page_detail_editor',
          viewKind: 'FORM',
          fields: [descriptorField('fileId', '上传文件')],
        },
      },
      traits: [],
    },
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
          formula: uiFormula('!(PRESENT({directoryId}))', unary('!', present('directoryId'))),
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

it('record form field state immediately switches module entry fields from their signed UI programs', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'entryRoute',
      {
        ...field('内部路由'),
        visible: {
          formula: uiFormula(
            "{entryType} == 'route'",
            binary('==', fieldNode('entryType'), valueNode('route')),
          ),
        },
      },
    ],
    [
      'entryExternalUrl',
      {
        ...field('外部链接'),
        visible: {
          formula: uiFormula(
            "{entryType} == 'link'",
            binary('==', fieldNode('entryType'), valueNode('link')),
          ),
        },
      },
    ],
  ]);

  assert.equal(
    resolveRecordFormFieldState('entryRoute', { fields, record: { entryType: 'route' } }).visible,
    true,
  );
  assert.equal(
    resolveRecordFormFieldState('entryExternalUrl', { fields, record: { entryType: 'route' } }).visible,
    false,
  );
  assert.equal(
    resolveRecordFormFieldState('entryRoute', { fields, record: { entryType: 'link' } }).visible,
    false,
  );
  assert.equal(
    resolveRecordFormFieldState('entryExternalUrl', { fields, record: { entryType: 'link' } }).visible,
    true,
  );
});

it('record form field state evaluates portable formula conjunctions for create-only editors', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'fileId',
      {
        ...field('文件标识'),
        readOnly: {
          formula: uiFormula(
            '!(PRESENT({directoryId}) && !(PRESENT({id})))',
            unary('!', binary('&&', present('directoryId'), unary('!', present('id')))),
          ),
        },
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

it('record form field state evaluates whitelisted literal predicates without JavaScript execution', () => {
  assert.equal(
    evaluateUiFormula(
      uiFormula(
        "{entryType} == 'link' && IN({moduleKind}, 'standard', 'system')",
        binary(
          '&&',
          binary('==', fieldNode('entryType'), valueNode('link')),
          inValues('moduleKind', 'standard', 'system'),
        ),
      ),
      { entryType: 'link', moduleKind: 'standard' },
    ),
    true,
  );
  assert.equal(
    evaluateUiFormula(
      uiFormula('{systemManaged} != true', binary('!=', fieldNode('systemManaged'), valueNode(true))),
      { systemManaged: true },
    ),
    false,
  );
  assert.equal(
    evaluateUiFormula(
      uiFormula(
        "{timestamp} == '2026-03-02T00:00:00Z'",
        binary('==', fieldNode('timestamp'), valueNode('2026-03-02T00:00:00Z')),
      ),
      { timestamp: '2026-03-02T00:00:00' },
    ),
    false,
  );
  assert.equal(
    evaluateUiFormula(uiFormula('{priority} == 2', binary('==', fieldNode('priority'), valueNode(2))), {
      priority: 2,
    }),
    true,
  );
  assert.equal(
    evaluateUiFormula(uiFormula('{priority} == 2', binary('==', fieldNode('priority'), valueNode(2))), {
      priority: '2',
    }),
    true,
  );
  assert.equal(
    evaluateUiFormula(uiFormula("IN({priority}, 2, '3')", inValues('priority', 2, '3')), { priority: '2' }),
    true,
  );
  assert.equal(
    evaluateUiFormula(
      uiFormula(
        "{timestamp} == '2026-03-02T00:00:00Z'",
        binary('==', fieldNode('timestamp'), valueNode('2026-03-02T00:00:00Z')),
      ),
      { timestamp: '2026-02-30T00:00:00Z' },
    ),
    false,
  );
  assert.equal(
    evaluateUiFormula(
      uiFormula(
        "PRESENT({missing}) && {priority} == 'not evaluated'",
        binary('&&', present('missing'), binary('==', fieldNode('priority'), valueNode('not evaluated'))),
      ),
      { priority: 2 },
    ),
    false,
  );
  assert.equal(evaluateUiFormula(uiFormula('PRESENT({name})', present('name')), { name: '' }), false);
  assert.equal(evaluateUiFormula({ expression: "window.alert('no')" }, {}), false);
  assert.equal(
    evaluateUiFormula(
      {
        expression: 'ignored',
        program: { schemaVersion: 2, profile: 'WEB_UI', root: present('name'), referencedFields: ['name'] },
      },
      { name: 'value' },
    ),
    false,
  );
  assert.equal(
    evaluateUiFormula(
      {
        expression: 'ignored',
        program: {
          schemaVersion: 1,
          profile: 'WEB_UI',
          root: { kind: 'ASSIGN', arguments: [] },
          referencedFields: [],
        },
      },
      { name: 'value' },
    ),
    false,
  );
});

it('record form field state retains a fallback disabled hint when its descriptor has none', () => {
  const fields = new Map<string, RecordFormFieldDescriptor>([
    [
      'fileId',
      {
        ...field('文件标识'),
        readOnly: { formula: uiFormula('!(PRESENT({directoryId}))', unary('!', present('directoryId'))) },
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

function uiFormula(expression: string, root: import('@muyun/web-contracts').FormulaNode) {
  return {
    expression,
    program: { schemaVersion: 1, profile: 'WEB_UI' as const, root, referencedFields: [] },
  };
}

function fieldNode(field: string): import('@muyun/web-contracts').FormulaNode {
  return { kind: 'FIELD', field, arguments: [] };
}

function valueNode(value: string | number | boolean): import('@muyun/web-contracts').FormulaNode {
  return { kind: 'VALUE', value, arguments: [] };
}

function unary(operator: string, argument: import('@muyun/web-contracts').FormulaNode) {
  return { kind: 'UNARY' as const, operator, arguments: [argument] };
}

function binary(
  operator: string,
  left: import('@muyun/web-contracts').FormulaNode,
  right: import('@muyun/web-contracts').FormulaNode,
) {
  return { kind: 'BINARY' as const, operator, arguments: [left, right] };
}

function present(field: string) {
  return { kind: 'FUNCTION' as const, operator: 'PRESENT', arguments: [fieldNode(field)] };
}

function inValues(field: string, ...values: Array<string | number | boolean>) {
  return {
    kind: 'FUNCTION' as const,
    operator: 'IN',
    arguments: [fieldNode(field), ...values.map(valueNode)],
  };
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
