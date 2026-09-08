import { expect, it } from 'vitest';
import { resolvePlacedPageActions } from '@/dynamic-page-runtime/pageActionPlacement';

it('only enables page actions with an execution workflow and keeps scope separate from importance', () => {
  const actions = resolvePlacedPageActions(
    [
      { actionCode: 'create', anchor: 'PAGE' },
      { actionCode: 'export', anchor: 'PAGE' },
      { actionCode: 'delete', anchor: 'PAGE' },
    ],
    'PAGE',
    (code) => ({ actionCode: code, actionLevel: code === 'delete' ? 'RECORD' : 'LIST' }),
    [],
    'view',
  );
  expect(actions.map((action) => action.actionCode)).toEqual(['create', 'export']);
  expect(actions[0]).toMatchObject({ disabled: false, actionLevel: 'standard' });
  expect(actions[1]).toMatchObject({ disabled: true, disabledReason: '该动作尚未提供当前区域的执行入口' });
});

it('reuses declared detail action blocks and rejects unimplemented record actions', () => {
  const actions = resolvePlacedPageActions(
    [
      { actionCode: 'approve', anchor: 'DETAIL' },
      { actionCode: 'unknown', anchor: 'DETAIL' },
    ],
    'DETAIL',
    (code) => ({ actionCode: code, actionLevel: 'RECORD' }),
    [{ key: 'configured-approve', actionCode: 'approve', title: '审批' }],
    'view',
  );
  expect(actions.map((action) => action.disabled)).toEqual([false, true]);
});

it.each(['create', 'edit', 'view'] as const)('only exposes the matching save action in %s mode', (mode) => {
  const actions = resolvePlacedPageActions(
    [
      { actionCode: 'create', anchor: 'FORM' },
      { actionCode: 'update', anchor: 'FORM' },
    ],
    'FORM',
    (code) => ({ actionCode: code }),
    [],
    mode,
  );
  expect(actions.map((action) => action.actionCode)).toEqual(
    mode === 'view' ? [] : [mode === 'create' ? 'create' : 'update'],
  );
});

it('gives the same action distinct interaction entries and mode-specific labels', () => {
  const placements = [
    { actionCode: 'create', anchor: 'PAGE' as const },
    { actionCode: 'create', anchor: 'FORM' as const },
    { actionCode: 'update', anchor: 'FORM' as const, title: '提交修改' },
  ];
  const actionOf = (actionCode: string) => ({ actionCode, actionLevel: 'ANY' });
  expect(resolvePlacedPageActions(placements, 'PAGE', actionOf, [], 'view', true)[0]?.title).toBe('新建');
  expect(
    resolvePlacedPageActions(placements, 'FORM', actionOf, [], 'create', true).map((item) => item.title),
  ).toEqual(['保存']);
  expect(
    resolvePlacedPageActions(placements, 'FORM', actionOf, [], 'edit', true).map((item) => item.title),
  ).toEqual(['提交修改']);
  expect(resolvePlacedPageActions(placements, 'FORM', actionOf, [], 'view', true)).toEqual([]);
});

it.each([true, false])('explains status operations that have already been applied (%s)', (enabled) => {
  const actions = resolvePlacedPageActions(
    [
      { actionCode: 'enable', anchor: 'DETAIL' },
      { actionCode: 'disable', anchor: 'DETAIL' },
    ],
    'DETAIL',
    (actionCode) => ({ actionCode, actionLevel: 'RECORD' }),
    [],
    'view',
    true,
    { enabled },
  );
  expect(actions).toHaveLength(1);
  expect(actions[0]).toMatchObject({ actionCode: enabled ? 'disable' : 'enable', disabled: false });
});

it('exposes an issued custom service action without borrowing a form submission', () => {
  const placements = [
    {
      actionCode: 'approve',
      anchor: 'DETAIL' as const,
      operation: 'INVOKE',
      invocation: { method: 'POST' as const, path: '/demo.order/{recordId}/approve', input: 'NONE' as const },
    },
  ];
  const actionOf = (actionCode: string) => ({
    actionCode,
    actionLevel: 'RECORD',
    category: 'CUSTOM',
    executorType: 'SERVICE',
    formSupported: true,
  });
  expect(resolvePlacedPageActions(placements, 'DETAIL', actionOf, [], 'view', true)[0]?.disabled).toBe(false);
  expect(resolvePlacedPageActions(placements, 'DETAIL', actionOf, [], 'edit', true)).toEqual([]);
});

it('exposes an ANY custom service action in the unsaved form context', () => {
  const action = {
    actionCode: 'recalculate',
    actionLevel: 'ANY',
    category: 'CUSTOM',
    executorType: 'SERVICE',
    formSupported: true,
  };
  const placement = [
    {
      actionCode: 'recalculate',
      anchor: 'FORM' as const,
      operation: 'INVOKE',
      invocation: {
        method: 'POST' as const,
        path: '/demo.order/form-actions/recalculate',
        input: 'FORM_RECORD' as const,
      },
    },
  ];
  const resolved = resolvePlacedPageActions(placement, 'FORM', () => action, [], 'edit', true);
  expect(resolved).toMatchObject([{ actionCode: 'recalculate', disabled: false }]);
  expect(resolvePlacedPageActions(placement, 'FORM', () => action, [], 'view', true)).toEqual([]);
});

it('does not expose an unbound action as executable even when authorized', () => {
  expect(
    resolvePlacedPageActions(
      [{ actionCode: 'approve', anchor: 'DETAIL', operation: 'INVOKE' }],
      'DETAIL',
      (actionCode) => ({ actionCode, actionLevel: 'RECORD', category: 'CUSTOM', bindingPending: true }),
      [],
      'view',
      true,
    ),
  ).toEqual([]);
});

it('does not enable a custom action merely because a service executor is declared', () => {
  const actions = resolvePlacedPageActions(
    [{ actionCode: 'approve', anchor: 'DETAIL', operation: 'INVOKE' }],
    'DETAIL',
    () => ({ actionCode: 'approve', actionLevel: 'RECORD', category: 'CUSTOM', executorType: 'SERVICE' }),
    [{ key: 'legacy', actionCode: 'approve', title: 'approve' }],
    'view',
    true,
  );
  expect(actions).toMatchObject([{ disabled: true }]);
});
