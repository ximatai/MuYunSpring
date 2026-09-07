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
  expect(actions.map((action) => action.disabled)).toEqual([enabled, !enabled]);
  expect(actions.find((action) => action.disabled)?.disabledReason).toBe(
    enabled ? '当前记录已启用' : '当前记录已停用',
  );
});
