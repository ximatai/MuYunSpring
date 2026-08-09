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

function formField(fieldName: string, options: Partial<RecordFormFieldState> = {}): RecordFormFieldState {
  return {
    fieldName,
    label: fieldName,
    required: false,
    readOnly: false,
    visible: true,
    controlType: 'input',
    ...options,
  };
}
