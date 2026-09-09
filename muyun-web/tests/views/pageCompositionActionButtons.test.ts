import { expect, it } from 'vitest';
import {
  actionButtons,
  actionButtonMembers,
  defaultPageActionEntries,
  withStandardActionEntries,
  canPlaceActionInAnchor,
} from '@/views/pageCompositionMode';
import { pageActionEntryVisible } from '@muyun/web-core';
const actions = [
  { actionCode: 'create', actionLevel: 'LIST' as const },
  { actionCode: 'update', actionLevel: 'RECORD' as const },
  { actionCode: 'enable', actionLevel: 'RECORD' as const },
  { actionCode: 'disable', actionLevel: 'RECORD' as const },
  {
    actionCode: 'approve',
    actionLevel: 'RECORD' as const,
    category: 'CUSTOM',
    executorType: 'SERVICE',
    invocations: {
      DETAIL: { method: 'POST' as const, path: '/demo.order/{recordId}/approve', input: 'NONE' as const },
    },
  },
];
it('binds standard buttons once while retaining their context-specific operations', () => {
  const entries = defaultPageActionEntries(actions);
  expect(entries.some((entry) => entry.actionCode === 'approve')).toBe(false);
  const form = actionButtons(entries.filter((entry) => entry.anchor === 'form'));
  expect(form).toHaveLength(1);
  expect(actionButtonMembers(entries, form[0]!).map((entry) => entry.actionCode)).toEqual([
    'create',
    'update',
  ]);
  expect(actionButtons(entries.filter((entry) => entry.anchor === 'detail'))).toHaveLength(2);
});
it('keeps old omissions hidden and makes the standard buttons recoverable', () => {
  const entries = withStandardActionEntries([], actions);
  expect(entries.every((entry) => entry.hidden)).toBe(true);
  expect(entries.every((entry) => !pageActionEntryVisible(entry, 'create'))).toBe(true);
  expect(withStandardActionEntries(entries, actions)).toEqual(entries);
});
it('keeps custom service actions in their declared scope', () => {
  expect(canPlaceActionInAnchor(actions[4], 'detail')).toBe(true);
  expect(canPlaceActionInAnchor(actions[4], 'page')).toBe(false);
  expect(canPlaceActionInAnchor(actions[4], 'form')).toBe(false);
  expect(canPlaceActionInAnchor({ ...actions[4]!, invocations: {} }, 'detail')).toBe(false);
});

it('allows pending layout before binding and requires explicit form capability afterwards', () => {
  const pending = { actionCode: 'calculate', category: 'CUSTOM', bindingPending: true };
  for (const anchor of ['page', 'detail', 'form'] as const) {
    expect(canPlaceActionInAnchor(pending, anchor)).toBe(true);
  }
  expect(canPlaceActionInAnchor({ ...pending, bindingPending: false, actionLevel: 'ANY' }, 'form')).toBe(
    false,
  );
  expect(
    canPlaceActionInAnchor(
      {
        ...pending,
        bindingPending: false,
        formSupported: true,
        invocations: {
          FORM: { method: 'POST', path: '/demo.order/form-actions/calculate', input: 'FORM_RECORD' },
        },
      },
      'form',
    ),
  ).toBe(true);
});

it('previews custom form placements before an execution operation has been compiled', () => {
  const entry = { actionCode: 'calculate', anchor: 'form' };
  expect(pageActionEntryVisible(entry, 'create')).toBe(true);
  expect(pageActionEntryVisible(entry, 'edit')).toBe(true);
  expect(pageActionEntryVisible(entry, 'view')).toBe(false);
});

it('requires an issued surface binding even for a classified custom action', () => {
  const action = {
    actionCode: 'sessionStatuses',
    actionLevel: 'LIST' as const,
    category: 'CUSTOM',
    executorType: 'SERVICE',
  };
  expect(canPlaceActionInAnchor(action, 'page')).toBe(false);
  expect(
    canPlaceActionInAnchor(
      {
        ...action,
        invocations: {
          PAGE: { method: 'GET', path: '/demo.order/status', input: 'NONE' },
        },
      },
      'page',
    ),
  ).toBe(true);
});

it('offers permissions as a detail preset and preserves hidden defaults in existing pages', () => {
  const actions = [{ actionCode: 'managePermissions', actionLevel: 'RECORD' as const }];
  expect(defaultPageActionEntries(actions)).toEqual([{ actionCode: 'managePermissions', anchor: 'detail' }]);
  expect(withStandardActionEntries([], actions)).toEqual([
    { actionCode: 'managePermissions', anchor: 'detail', hidden: true },
  ]);
  expect(canPlaceActionInAnchor(actions[0], 'page')).toBe(false);
  expect(canPlaceActionInAnchor(actions[0], 'form')).toBe(false);
});

it('defaults authorization immediately before edit without reordering saved placements', () => {
  const actions = ['update', 'delete', 'managePermissions'].map((actionCode) => ({
    actionCode,
    actionLevel: 'RECORD' as const,
  }));
  expect(
    defaultPageActionEntries(actions)
      .filter((entry) => entry.anchor === 'detail')
      .map((entry) => entry.actionCode),
  ).toEqual(['managePermissions', 'update', 'delete']);
  const saved = [
    { actionCode: 'update', anchor: 'detail' as const },
    { actionCode: 'delete', anchor: 'detail' as const },
    { actionCode: 'managePermissions', anchor: 'detail' as const },
  ];
  expect(withStandardActionEntries(saved, actions).filter((entry) => entry.anchor === 'detail')).toEqual(
    saved,
  );
});
