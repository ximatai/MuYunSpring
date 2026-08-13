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
      {
        key: 'delete',
        actionCode: 'delete',
        title: '删除',
        danger: true,
        disabledReason: '当前用户没有删除权限',
      },
      { key: 'hidden', title: '隐藏', visible: false },
    ],
  );

  assert.deepEqual(
    actions.map((action) => ({
      key: action.key,
      authorized: action.authorized,
      disabled: action.disabled,
      disabledReason: action.disabledReason,
      danger: action.danger,
    })),
    [
      { key: 'edit', authorized: true, disabled: false, disabledReason: undefined, danger: undefined },
      {
        key: 'delete',
        authorized: false,
        disabled: true,
        disabledReason: '当前用户没有删除权限',
        danger: true,
      },
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
      runtime: { snapshot: () => ({}) },
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

it('resolveRecordActions keeps actions visible but disabled while runtime context is loading', () => {
  const actions = resolveRecordActions(
    {
      action: () => undefined,
      runtime: { snapshot: () => undefined },
    },
    [{ key: 'create', actionCode: 'create', title: '新建' }],
  );

  assert.equal(actions.length, 1);
  assert.isTrue(actions[0].disabled);
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

it('resolveRecordActions uses an attached scope authorization context and record ID', () => {
  const pageContext = { action: () => ({ available: false }) };
  const calls: Array<[string, string | undefined]> = [];
  const scopeContext = {
    action: (actionCode: string, recordId?: string) => {
      calls.push([actionCode, recordId]);
      return { actionCode, available: true };
    },
  };

  const actions = resolveRecordActions(pageContext, [
    {
      key: 'ask',
      actionCode: 'agent_chat_ask',
      title: '模拟问答',
      authorizationContext: scopeContext as never,
      authorizationRecordId: 'directory-1',
    },
  ]);

  assert.deepEqual(calls, [['agent_chat_ask', 'directory-1']]);
  assert.isTrue(actions[0].authorized);
  assert.isFalse(actions[0].disabled);
});

it('resolveRecordActions denies scope-record authorization until a scope record is selected', () => {
  const scopeContext = { action: () => ({ available: true }) };

  const actions = resolveRecordActions({ action: () => ({ available: true }) }, [
    {
      key: 'ask',
      actionCode: 'agent_chat_ask',
      title: '模拟问答',
      authorizationContext: scopeContext as never,
    },
  ]);

  assert.isFalse(actions[0].authorized);
  assert.isTrue(actions[0].disabled);
  assert.equal(actions[0].reason, '请先选择作用域记录');
});

it('resolveRecordActions retains a record-level authorization reason for disabled-action feedback', () => {
  const actions = resolveRecordActions(
    {
      action: (_actionCode, recordId) => ({
        available: false,
        reason: `无权操作记录 ${recordId}`,
      }),
    },
    [{ key: 'delete', actionCode: 'delete', title: '删除' }],
    false,
    'knowledge-file-1',
  );

  assert.equal(actions[0].disabled, true);
  assert.equal(actions[0].reason, '无权操作记录 knowledge-file-1');
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
