import { assert, it } from 'vitest';
import { resolveRecordDetailDisplayValue } from '@/platform-components/recordDetailFieldModel.ts';
import type { RecordFormFieldState } from '@/platform-components/recordFormFieldModel.ts';

it('record detail display resolves select label and empty text through default rules', () => {
  const field = formField('gender', {
    controlType: 'select',
    options: [
      { label: '男', value: 'MALE' },
      { label: '女', value: 'FEMALE' },
    ],
  });

  assert.equal(resolveRecordDetailDisplayValue(field, { gender: 'MALE' }), '男');
  assert.equal(resolveRecordDetailDisplayValue(field, { gender: '' }), '-');
  assert.equal(resolveRecordDetailDisplayValue(field, { gender: '' }, { emptyText: '未填写' }), '未填写');
});

it('record detail display keeps default rules when custom resolver does not handle a field', () => {
  const field = formField('gender', {
    controlType: 'select',
    options: [{ label: '女', value: 'FEMALE' }],
  });

  assert.equal(
    resolveRecordDetailDisplayValue(field, { gender: 'FEMALE' }, { displayOf: () => undefined }),
    '女',
  );
});

it('record detail display prefers the server-projected option title', () => {
  const field = formField('gender', {
    controlType: 'select',
    hasOption: true,
    optionTitleField: 'genderTitle',
  });

  assert.equal(resolveRecordDetailDisplayValue(field, { gender: '1', genderTitle: '男' }), '男');
});

it('record detail display resolves runtime option codes through the source-neutral option catalog', () => {
  const field = formField('moduleKind', {
    controlType: 'select',
    hasOption: true,
  });

  assert.equal(
    resolveRecordDetailDisplayValue(
      field,
      { moduleKind: 'static' },
      {
        optionItems: [
          { code: 'static', title: '静态模块', enabled: true },
          { code: 'dynamic', title: '动态模块', enabled: true },
        ],
      },
    ),
    '静态模块',
  );
});

it('record detail display renders a virtual tree root as the standard empty value', () => {
  const field = formField('parentId', { treeRootTitle: '根模块' });
  assert.equal(resolveRecordDetailDisplayValue(field, { parentId: 'root' }), '-');
  assert.equal(resolveRecordDetailDisplayValue(field, { parentId: 'ordinary-root' }), 'ordinary-root');
});

it('record detail display resolves record picker object with configured title', () => {
  const field = formField('department', {
    controlType: 'recordPicker',
    pickerConfig: {
      context: {} as never,
      titleOf: (record) => `${record.code} ${record.title}`,
    },
  });

  assert.equal(
    resolveRecordDetailDisplayValue(field, {
      department: { id: 'dept-1', code: 'D01', title: '研发部' },
    }),
    'D01 研发部',
  );
});

it('record detail display prefers the server-projected reference title for a scalar picker id', () => {
  const field = formField('directoryId', {
    controlType: 'recordPicker',
    referenceTitleField: 'directoryTitle',
  });

  assert.equal(
    resolveRecordDetailDisplayValue(field, {
      directoryId: 'mr_demo_knowledge_earthwork',
      directoryTitle: '土石方施工',
    }),
    '土石方施工',
  );
});

it('record detail display joins server-projected titles for a multi-record picker', () => {
  const field = formField('uiControlAliases', {
    controlType: 'recordMultiPicker',
    referenceTitleField: 'uiControlTitles',
  });

  assert.equal(
    resolveRecordDetailDisplayValue(field, {
      uiControlAliases: ['input', 'textarea'],
      uiControlTitles: ['单行输入', '多行输入'],
    }),
    '单行输入、多行输入',
  );
});

function formField(fieldName: string, options: Partial<RecordFormFieldState> = {}): RecordFormFieldState {
  return {
    fieldName,
    label: fieldName,
    required: false,
    readOnly: false,
    visible: true,
    controlType: 'input',
    columnSpan: 1,
    hasOption: false,
    ...options,
  };
}
