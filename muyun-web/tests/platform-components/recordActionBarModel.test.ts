import { assert, it } from 'vitest';
import { mergeRecordActions, resolveRecordActions } from '@/platform-components/recordActionBarModel.ts';

it('resolveRecordActions filters invisible actions and applies authorization', () => {
  const actions = resolveRecordActions(
    {
      action: (actionCode) => ({
        actionCode,
        available: actionCode !== 'delete',
      }),
    },
    [
      { key: 'edit', actionCode: 'update', title: '编辑' },
      { key: 'delete', actionCode: 'delete', title: '删除', danger: true },
      { key: 'hidden', title: '隐藏', visible: false },
    ],
  );

  assert.deepEqual(
    actions.map((action) => ({
      key: action.key,
      authorized: action.authorized,
      disabled: action.disabled,
      danger: action.danger,
    })),
    [
      { key: 'edit', authorized: true, disabled: false, danger: undefined },
      { key: 'delete', authorized: false, disabled: true, danger: true },
    ],
  );
});

it('resolveRecordActions omits actions that the module does not publish', () => {
  const actions = resolveRecordActions(
    {
      action: (actionCode) =>
        actionCode === 'create'
          ? undefined
          : {
              actionCode,
              available: true,
            },
    },
    [
      { key: 'create', actionCode: 'create', title: '新建' },
      { key: 'query', actionCode: 'query', title: '查询' },
    ],
  );

  assert.deepEqual(
    actions.map((action) => action.actionCode),
    ['query'],
  );
});

it('resolveRecordActions applies default and per-action loading', () => {
  const actions = resolveRecordActions(
    { action: (actionCode) => ({ actionCode, available: true }) },
    [
      { key: 'cancel', title: '取消', loading: false },
      { key: 'save', actionCode: 'update', title: '保存', primary: true },
    ],
    true,
  );

  assert.equal(actions[0].loading, false);
  assert.equal(actions[0].disabled, false);
  assert.equal(actions[1].loading, true);
  assert.equal(actions[1].disabled, true);
  assert.equal(actions[1].primary, true);
});

it('resolveRecordActions passes record id into authorization check', () => {
  const calls: Array<[string, string | undefined]> = [];
  const actions = resolveRecordActions(
    {
      action: (actionCode, recordId) => {
        calls.push([actionCode, recordId]);
        return {
          actionCode,
          available: recordId === 'user-1' && actionCode === 'update',
          reason: actionCode === 'resetPassword' ? 'cannot reset current user' : undefined,
        };
      },
    },
    [
      { key: 'edit', actionCode: 'update', title: '编辑' },
      { key: 'reset', actionCode: 'resetPassword', title: '重置密码' },
    ],
    false,
    'user-1',
  );

  assert.deepEqual(calls, [
    ['update', 'user-1'],
    ['resetPassword', 'user-1'],
  ]);
  assert.equal(actions[0].disabled, false);
  assert.equal(actions[1].disabled, true);
  assert.equal(actions[1].reason, 'cannot reset current user');
});

it('mergeRecordActions inserts extension actions around standard anchors', () => {
  const actions = mergeRecordActions(
    [
      { key: 'view', title: '查看' },
      { key: 'edit', actionCode: 'update', title: '修改' },
      { key: 'delete', actionCode: 'delete', title: '删除' },
    ],
    [
      { key: 'bind', title: '绑定', after: 'update' },
      { key: 'toggle', title: '停用', before: 'delete' },
      { key: 'audit', title: '审计', after: 'missing' },
    ],
  );

  assert.deepEqual(
    actions.map((action) => action.key),
    ['view', 'edit', 'bind', 'toggle', 'delete', 'audit'],
  );
});
