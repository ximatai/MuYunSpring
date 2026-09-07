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
