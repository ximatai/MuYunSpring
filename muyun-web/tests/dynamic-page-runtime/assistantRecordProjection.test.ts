import { expect, it } from 'vitest';
import {
  assistantFieldDisplay,
  assistantConfirmationFieldDisplay,
  assistantRelationProjection,
} from '@/dynamic-page-runtime/assistantRecordProjection';
import { resolveRecordFormFieldState, resolveRecordFormFields } from '@muyun/platform-components';
import type { ResolvedModuleUiDescriptor, ResolvedDetailRelationDescriptor } from '@muyun/web-contracts';

const descriptor = {
  editorContributions: [
    {
      resource: 'member',
      editor: {
        fields: [
          { fieldRef: { fieldName: 'name' }, label: '学生', visible: { constant: true } },
          {
            fieldRef: { fieldName: 'secret' },
            label: '秘密',
            assistantPolicy: 'HIDDEN',
            visible: { constant: true },
          },
        ],
      },
    },
  ],
} as unknown as ResolvedModuleUiDescriptor;
const relation = {
  code: 'members',
  title: '成员',
  embeddedField: 'members',
  targetEntityAlias: 'member',
} as ResolvedDetailRelationDescriptor;

it('marks unsaved calculated values in context and confirmation while retaining saved facts', () => {
  const calculated = structuredClone(descriptor);
  calculated.editorContributions![0]!.editor.fields = [
    {
      fieldRef: { fieldName: 'amount' },
      label: '小计',
      readOnly: { constant: true },
      calculationTiming: 'ON_SAVE',
    },
  ];
  const record = { members: [{ id: '1', amount: '65.00' }] };
  const baseline = { members: [{ id: '2', amount: '20.00' }] };
  for (const options of [{ draft: true }, { purpose: 'confirmation' as const }]) {
    const result = assistantRelationProjection(calculated, [relation], record, { ...options, baseline });
    expect(result[0]!.rows[0]!.values[0]!.value).toBe('保存后计算');
    expect(result[0]!.removedRows[0]!.values[0]!.value).toBe('20.00');
  }
  expect(assistantRelationProjection(calculated, [relation], record)[0]!.rows[0]!.values[0]!.value).toBe(
    '65.00',
  );
});

it('projects aggregate rows and removals without revealing hidden fields or inventing edit capability', () => {
  const result = assistantRelationProjection(
    descriptor,
    [relation],
    {
      members: [{ id: '1', name: '陈晨', secret: 'hidden' }, { name: '林晓' }],
    },
    {
      baseline: {
        members: [
          { id: '1', name: '陈晨' },
          { id: '2', name: '旧成员' },
        ],
      },
    },
  );
  expect(result[0]).toMatchObject({ count: 2, removedCount: 1, assistantWritable: false, truncated: false });
  expect(JSON.stringify(result)).toContain('陈晨');
  expect(JSON.stringify(result)).toContain('旧成员');
  expect(JSON.stringify(result)).not.toContain('hidden');
  expect(
    assistantRelationProjection(descriptor, [relation], {
      members: Array.from({ length: 21 }, () => ({ name: '陈晨' })),
    })[0],
  ).toMatchObject({ count: 21, truncated: true });
  expect(
    assistantRelationProjection(descriptor, [{ ...relation, visible: { constant: false } }], {}),
  ).toEqual([]);
});

it('distinguishes an unselected reference from a selected reference with an unavailable label', () => {
  const ui = {
    defaultEditor: {
      fields: [
        {
          fieldRef: { fieldName: 'teacherId' },
          label: '班主任',
          reference: { targetModuleAlias: 'education.teacher', cardinality: 'ONE' },
        },
      ],
    },
  } as unknown as ResolvedModuleUiDescriptor;
  const fields = resolveRecordFormFields(ui);
  const state = resolveRecordFormFieldState('teacherId', { fields, record: {} })!;
  state.referenceTitleField = 'teacherTitle';
  expect(assistantFieldDisplay(state, {})).toBe('未选择');
  expect(assistantFieldDisplay(state, { teacherId: 'internal-id' })).toBe('已选择（名称暂不可用）');
  expect(assistantFieldDisplay(state, { teacherId: 'internal-id', teacherTitle: '王老师' })).toBe('王老师');
});

it('does not interpret an unloaded aggregate as empty or removed', () => {
  expect(
    assistantRelationProjection(
      descriptor,
      [relation],
      { id: 'parent' },
      { baseline: { members: [{ id: 'child', name: '陈晨' }] } },
    )[0],
  ).toMatchObject({ loaded: false, count: null, removedCount: 0, rows: [], removedRows: [] });
});

it('uses business column titles and loaded scoped option labels for confirmation details', () => {
  const ui = {
    editorContributions: [
      {
        resource: 'member',
        editor: {
          fields: [
            { fieldRef: { fieldName: 'attendanceStatus' }, option: { binding: { type: 'DICTIONARY' } } },
          ],
        },
      },
    ],
  } as unknown as ResolvedModuleUiDescriptor;
  const detail = {
    ...relation,
    queryContract: { listProjection: { fields: [{ fieldName: 'attendanceStatus', title: '参加状态' }] } },
  } as ResolvedDetailRelationDescriptor;
  const result = assistantRelationProjection(
    ui,
    [detail],
    { members: [{ attendanceStatus: 'ATTENDED' }] },
    {
      relationOptions: {
        members: { attendanceStatus: [{ code: 'ATTENDED', title: '已参加', enabled: true }] },
      },
    },
  );
  expect(result[0]?.rows[0]?.values).toEqual([{ label: '参加状态', value: '已参加' }]);
});

it('shows the complete human confirmation while keeping hidden relation values private', () => {
  const record = { members: Array.from({ length: 21 }, () => ({ name: '内容'.repeat(1200) })) };
  const context = assistantRelationProjection(descriptor, [relation], record)[0]!;
  const confirmation = assistantRelationProjection(descriptor, [relation], record, {
    purpose: 'confirmation',
  })[0]!;
  expect(context.rows).toHaveLength(20);
  expect(context.rows[0]?.values[0]?.value).toHaveLength(2000);
  expect(confirmation.rows).toHaveLength(21);
  expect(confirmation.rows[0]?.values[0]?.value).toHaveLength(2400);
  expect(confirmation.truncated).toBe(false);
  const hidden = assistantRelationProjection(
    descriptor,
    [{ ...relation, visible: { constant: false } }],
    record,
    { purpose: 'confirmation' },
  )[0]!;
  expect(hidden.count).toBe(21);
  expect(hidden.rows.every((row) => row.values.length === 0)).toBe(true);
});

it('resolves confirmation reference names through the picker without changing the draft', async () => {
  const { assistantConfirmationFieldDisplay } =
    await import('@/dynamic-page-runtime/assistantRecordProjection');
  const { vi } = await import('vitest');
  const record = { customerId: 'customer-1' };
  const resolve = vi.fn(async () => [
    { id: 'customer-1', title: '试用客户', affectPatch: { secret: 'ignored' } },
  ]);
  const field = {
    fieldName: 'customerId',
    reference: { cardinality: 'ONE' },
    pickerConfig: { provider: { resolve } },
  } as unknown as import('@muyun/platform-components').RecordFormFieldState;
  expect(await assistantConfirmationFieldDisplay(field, record)).toBe('试用客户');
  expect(resolve).toHaveBeenCalledWith(['customer-1']);
  expect(record).toEqual({ customerId: 'customer-1' });
  resolve.mockResolvedValueOnce([{ id: 'another', title: '其他客户', affectPatch: { secret: 'ignored' } }]);
  expect(await assistantConfirmationFieldDisplay(field, record)).toBe('已选择（名称暂不可用）');
  resolve.mockRejectedValueOnce(new Error('permission changed'));
  await expect(assistantConfirmationFieldDisplay(field, record)).rejects.toThrow('permission changed');
});

it('retains authorized belonging context in a same-title reference confirmation', async () => {
  const ui = {
    defaultEditor: {
      fields: [
        {
          fieldRef: { fieldName: 'moduleId' },
          label: '模块',
          reference: { targetModuleAlias: 'platform.module', cardinality: 'ONE' },
        },
      ],
    },
  } as unknown as ResolvedModuleUiDescriptor;
  const fields = resolveRecordFormFields(ui);
  const state = resolveRecordFormFieldState('moduleId', { fields, record: {} })!;
  state.pickerConfig = {
    provider: { resolve: async () => [{ id: 'north', title: '客户', subtitle: '北店' }] },
  } as never;
  expect(await assistantConfirmationFieldDisplay(state, { moduleId: 'north' })).toBe('客户 · 北店');
});
