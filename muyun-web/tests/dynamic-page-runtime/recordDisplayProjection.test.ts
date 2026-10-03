import { expect, it, vi } from 'vitest';
import {
  recordFieldDisplay,
  resolvedRecordFieldDisplay,
  recordRelationProjection,
} from '@/dynamic-page-runtime/recordDisplayProjection';
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

it('projects aggregate completeness and editability without assistant instructions', () => {
  const record = { members: [{ name: '陈晨' }] };
  const read = recordRelationProjection(descriptor, [relation], record)[0]!;
  expect(read).toMatchObject({ loaded: true, editable: false, count: 1 });
  expect(read).not.toHaveProperty('operationBoundary');
  const edit = recordRelationProjection(descriptor, [relation], record, {
    draft: true,
    editableRelations: new Set(['members']),
  })[0]!;
  expect(edit.editable).toBe(true);
});

it('shows editor enablement defaults in confirmation without inventing saved or ordinary boolean values', async () => {
  const enabled = resolveRecordFormFieldState('enabled', {
    fallback: { enabled: { controlType: 'enabledStatus', label: '启用状态' } },
  });
  const ordinary = resolveRecordFormFieldState('checked', {
    fallback: { checked: { controlType: 'switch', label: '选择' } },
  });
  expect(await resolvedRecordFieldDisplay(enabled, {})).toBe('启用');
  expect(await resolvedRecordFieldDisplay(enabled, { enabled: false })).toBe('停用');
  expect(await resolvedRecordFieldDisplay(ordinary, {})).toBe('空');
  expect(recordFieldDisplay(enabled, {})).toBe('空');
});

it('does not resolve a stale computed reference for save confirmation', async () => {
  const fields = resolveRecordFormFields({
    defaultEditor: {
      fields: [
        {
          fieldRef: { fieldName: 'ownerId' },
          calculationTiming: 'ON_SAVE',
          readOnly: { constant: true },
          reference: { targetModuleAlias: 'iam.user', cardinality: 'ONE' },
        },
      ],
    },
  } as unknown as ResolvedModuleUiDescriptor);
  const field = resolveRecordFormFieldState('ownerId', { fields, mode: 'edit' });
  const resolve = vi.fn(async () => [{ id: 'old', title: '旧负责人' }]);
  field.pickerConfig = { provider: { resolve } } as unknown as typeof field.pickerConfig;
  expect(await resolvedRecordFieldDisplay(field, { ownerId: 'old' })).toBe('保存后计算');
  expect(resolve).not.toHaveBeenCalled();
});

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
    const result = recordRelationProjection(calculated, [relation], record, { ...options, baseline });
    expect(result[0]!.rows[0]!.values[0]!.value).toBe('保存后计算');
    expect(result[0]!.removedRows[0]!.values[0]!.value).toBe('20.00');
  }
  expect(recordRelationProjection(calculated, [relation], record)[0]!.rows[0]!.values[0]!.value).toBe(
    '65.00',
  );
});

it('projects aggregate rows and removals without revealing hidden fields or inventing edit capability', () => {
  const result = recordRelationProjection(
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
  expect(result[0]).toMatchObject({ count: 2, removedCount: 1, editable: false, truncated: false });
  expect(JSON.stringify(result)).toContain('陈晨');
  expect(JSON.stringify(result)).toContain('旧成员');
  expect(JSON.stringify(result)).not.toContain('hidden');
  expect(
    recordRelationProjection(descriptor, [relation], {
      members: Array.from({ length: 21 }, () => ({ name: '陈晨' })),
    })[0],
  ).toMatchObject({ count: 21, truncated: true });
  expect(recordRelationProjection(descriptor, [{ ...relation, visible: { constant: false } }], {})).toEqual(
    [],
  );
});

it('projects saved rows separately from reordered draft rows without recalculating the baseline', () => {
  const ui = structuredClone(descriptor);
  ui.editorContributions![0]!.editor.fields = [
    {
      fieldRef: { fieldName: 'amount' },
      label: '小计',
      readOnly: { constant: true },
      calculationTiming: 'ON_SAVE',
    },
    { fieldRef: { fieldName: 'secret' }, assistantPolicy: 'HIDDEN' },
  ];
  const baseline = {
    members: [
      { id: 'a', amount: '213.60', secret: 'hidden-a' },
      { id: 'b', amount: '44.00' },
    ],
  };
  const current = { members: [{ id: 'b', amount: '59.50' }, { amount: '153.00' }] };
  const projected = recordRelationProjection(ui, [relation], current, {
    purpose: 'confirmation',
    baseline,
    includeBaseline: true,
  })[0]!;
  expect(projected.savedCount).toBe(2);
  expect(projected.savedRows?.map((row) => row.values[0]?.value)).toEqual(['213.60', '44.00']);
  expect(projected.rows.map((row) => row.values[0]?.value)).toEqual(['保存后计算', '保存后计算']);
  expect(projected.removedRows[0]?.values[0]?.value).toBe('213.60');
  expect(JSON.stringify(projected)).not.toContain('hidden-a');
  expect(baseline.members[0]?.amount).toBe('213.60');
  expect(
    recordRelationProjection(ui, [relation], current, { includeBaseline: true })[0]!.savedCount,
  ).toBeNull();
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
  expect(recordFieldDisplay(state, {})).toBe('未选择');
  expect(recordFieldDisplay(state, { teacherId: 'internal-id' })).toBe('已选择（名称暂不可用）');
  expect(recordFieldDisplay(state, { teacherId: 'internal-id', teacherTitle: '王老师' })).toBe('王老师');
});

it('separates computed draft codes and references from saved title projections', () => {
  const fields = resolveRecordFormFields({
    defaultEditor: {
      fields: [
        {
          fieldRef: { fieldName: 'status' },
          readOnly: { constant: true },
          calculationTiming: 'IMMEDIATE',
          option: {
            titleField: 'statusTitle',
            inlineItems: [{ code: 'B', title: '已处理', enabled: true }],
          },
        },
        {
          fieldRef: { fieldName: 'ownerId' },
          readOnly: { constant: true },
          calculationTiming: 'IMMEDIATE',
          reference: { targetModuleAlias: 'iam.user', cardinality: 'ONE', titleField: 'ownerTitle' },
        },
      ],
    },
  } as unknown as ResolvedModuleUiDescriptor);
  const record = { status: 'B', statusTitle: '待处理', ownerId: 'user-b', ownerTitle: '甲用户' };
  const status = resolveRecordFormFieldState('status', { fields, mode: 'edit' });
  const owner = resolveRecordFormFieldState('ownerId', { fields, mode: 'edit' });
  expect(recordFieldDisplay(status, record, undefined, true)).toBe('已处理');
  expect(recordFieldDisplay(owner, record, undefined, true)).toBe('已选择（名称暂不可用）');
  expect(recordFieldDisplay(status, record)).toBe('待处理');
  expect(recordFieldDisplay(owner, record)).toBe('甲用户');
});

it('does not interpret an unloaded aggregate as empty or removed', () => {
  expect(
    recordRelationProjection(
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
  const result = recordRelationProjection(
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
  const context = recordRelationProjection(descriptor, [relation], record)[0]!;
  const confirmation = recordRelationProjection(descriptor, [relation], record, {
    purpose: 'confirmation',
  })[0]!;
  expect(context.rows).toHaveLength(20);
  expect(context.rows[0]?.values[0]?.value).toHaveLength(2000);
  expect(confirmation.rows).toHaveLength(21);
  expect(confirmation.rows[0]?.values[0]?.value).toHaveLength(2400);
  expect(confirmation.truncated).toBe(false);
  const hidden = recordRelationProjection(
    descriptor,
    [{ ...relation, visible: { constant: false } }],
    record,
    { purpose: 'confirmation' },
  )[0]!;
  expect(hidden.count).toBe(21);
  expect(hidden.rows.every((row) => row.values.length === 0)).toBe(true);
});

it('includes described child values only for human review and always keeps hidden values private', () => {
  const humanDescriptor = {
    editorContributions: [
      {
        resource: 'member',
        editor: {
          fields: [
            ...descriptor.editorContributions![0]!.editor.fields,
            {
              fieldRef: { fieldName: 'note' },
              label: '人工备注',
              assistantPolicy: 'DESCRIBE',
              visible: { constant: true },
            },
          ],
        },
      },
    ],
  } as unknown as ResolvedModuleUiDescriptor;
  const record = { members: [{ name: '陈晨', note: '人工核对内容', secret: '不应出现' }] };
  const model = recordRelationProjection(humanDescriptor, [relation], record, { purpose: 'confirmation' });
  expect(JSON.stringify(model)).not.toContain('人工核对内容');
  const human = recordRelationProjection(humanDescriptor, [relation], record, {
    purpose: 'confirmation',
    includeDescribedValues: true,
  });
  expect(human[0]?.rows[0]?.values).toContainEqual({
    label: '人工备注',
    assistantPolicy: 'DESCRIBE',
    value: '人工核对内容',
  });
  expect(JSON.stringify(human)).not.toContain('不应出现');
});

it('resolves confirmation reference names through the picker without changing the draft', async () => {
  const { resolvedRecordFieldDisplay } = await import('@/dynamic-page-runtime/recordDisplayProjection');
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
  expect(await resolvedRecordFieldDisplay(field, record)).toBe('试用客户');
  expect(resolve).toHaveBeenCalledWith(['customer-1']);
  expect(record).toEqual({ customerId: 'customer-1' });
  resolve.mockResolvedValueOnce([{ id: 'another', title: '其他客户', affectPatch: { secret: 'ignored' } }]);
  expect(await resolvedRecordFieldDisplay(field, record)).toBe('已选择（名称暂不可用）');
  resolve.mockRejectedValueOnce(new Error('permission changed'));
  await expect(resolvedRecordFieldDisplay(field, record)).rejects.toThrow('permission changed');
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
  expect(await resolvedRecordFieldDisplay(state, { moduleId: 'north' })).toBe('客户 · 北店');
});

it('excludes declared identity fields only from historical display observations', () => {
  const ui = structuredClone(descriptor);
  ui.editorContributions![0]!.editor.fields.push(
    { fieldRef: { fieldName: 'id' }, label: '行标识', visible: { constant: true } },
    { fieldRef: { fieldName: 'version' }, label: '行版本', visible: { constant: true } },
  );
  const record = { members: [{ id: 'line-private-id', version: 91, name: '墨水' }] };
  const observed = recordRelationProjection(ui, [relation], record, { purpose: 'observation' });
  expect(observed[0]!.rows[0]!.values).toEqual([{ label: '学生', value: '墨水' }]);
  expect(JSON.stringify(observed)).not.toContain('line-private-id');
  expect(JSON.stringify(recordRelationProjection(ui, [relation], record))).toContain('line-private-id');
});

it('marks a truncated saved baseline even when the current draft is empty', () => {
  const facts = recordRelationProjection(
    descriptor,
    [relation],
    { members: [] },
    {
      includeBaseline: true,
      baseline: { members: Array.from({ length: 21 }, () => ({ name: '旧成员' })) },
      draft: true,
    },
  )[0]!;
  expect(facts).toMatchObject({ savedCount: 21, truncated: true, source: 'unsaved-draft' });
  expect(facts.savedRows).toHaveLength(20);
});
