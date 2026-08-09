import { assert, it } from 'vitest';
import {
  createPlatformActionResultReactionHandlers,
  handlePlatformActionSuccess,
  platformActionResultReactions,
  platformActionResultReactionTypes,
  resolvePlatformActionResult,
  resolvePlatformActionResultMessage,
  withPlatformActionResultReactions,
} from '@/platform-components/platformActionResultFeedback.ts';
import { matchesPlatformActionErrorHandler } from '@/platform-components/platformErrorFeedback.ts';
import { AppError, platformErrorCodes } from '@/web-core/index.ts';

it('platform action error handler matches by code or marker facts', () => {
  const codedError = new AppError('resource conflict', {
    code: platformErrorCodes.resourceInUse,
    details: { marker: 'dictionaryCategory' },
  });
  const reasonError = new AppError('position is referenced by employees', {
    code: platformErrorCodes.internalError,
    details: { reason: 'position' },
  });
  const errorKeyError = new AppError('employee is referenced by accounts', {
    details: { errorKey: 'employee' },
  });
  const messageMarkerError = new AppError('dictionaryCategory still exists');

  assert.equal(
    matchesPlatformActionErrorHandler(codedError, {
      code: platformErrorCodes.resourceInUse,
      handle: () => undefined,
    }),
    true,
  );
  assert.equal(
    matchesPlatformActionErrorHandler(codedError, {
      marker: 'dictionaryCategory',
      handle: () => undefined,
    }),
    true,
  );
  assert.equal(
    matchesPlatformActionErrorHandler(reasonError, {
      marker: 'position',
      handle: () => undefined,
    }),
    true,
  );
  assert.equal(
    matchesPlatformActionErrorHandler(errorKeyError, {
      marker: 'employee',
      handle: () => undefined,
    }),
    true,
  );
  assert.equal(
    matchesPlatformActionErrorHandler(messageMarkerError, {
      marker: 'dictionaryCategory',
      handle: () => undefined,
    }),
    true,
  );
  assert.equal(
    matchesPlatformActionErrorHandler(codedError, {
      marker: 'employee',
      handle: () => undefined,
    }),
    false,
  );
});

it('platform action result message prefers business message and falls back safely', () => {
  assert.equal(resolvePlatformActionResultMessage({ message: '已保存' }), '已保存');
  assert.equal(
    resolvePlatformActionResultMessage({
      message: { code: 'iam.employee-account.provisioned', text: '账号已创建并绑定职员', type: 'SUCCESS' },
    }),
    '账号已创建并绑定职员',
  );
  assert.equal(resolvePlatformActionResultMessage({ message: '   ' }, '默认成功'), '默认成功');
  assert.equal(resolvePlatformActionResultMessage(1, '已删除'), '已删除');
  assert.equal(resolvePlatformActionResultMessage(undefined), '操作成功');
});

it('platform action result resolves data changes without dispatching UI reactions', async () => {
  const actionResult = resolvePlatformActionResult({
    message: '已刷新',
    resultType: 'updated',
    changes: [
      { type: 'record-updated', moduleAlias: 'iam.employee', recordId: 'emp-1' },
      { type: '' },
      'refresh-detail',
      { type: 'record-updated' },
    ],
  });

  assert.equal(actionResult.message, '已刷新');
  assert.equal(actionResult.resultType, 'updated');
  assert.deepEqual(actionResult.changes, [
    { type: 'record-updated', moduleAlias: 'iam.employee', recordId: 'emp-1' },
  ]);
  assert.deepEqual(actionResult.reactions, []);

  const handled: string[] = [];
  await handlePlatformActionSuccess(actionResult.raw, {
    reactionHandlers: {
      'refresh-list': (reaction, result) => {
        handled.push(`${reaction.type}:${result.resultType}`);
      },
    },
  });
  assert.deepEqual(handled, []);
});

it('platform action result exposes message metadata and change set id', () => {
  const actionResult = resolvePlatformActionResult({
    message: { code: 'iam.employee-account.removed', text: '账户已移除', type: 'SUCCESS' },
    changeSetId: 'change-set-1',
  });

  assert.equal(actionResult.message, '账户已移除');
  assert.equal(actionResult.messageCode, 'iam.employee-account.removed');
  assert.equal(actionResult.messageType, 'SUCCESS');
  assert.equal(actionResult.changeSetId, 'change-set-1');
});

it('platform action result preserves finite backend message types', () => {
  assert.equal(
    resolvePlatformActionResult({ message: { text: '部分完成', type: 'WARNING' } }).messageType,
    'WARNING',
  );
  assert.equal(
    resolvePlatformActionResult({ message: { text: '处理中', type: 'INFO' } }).messageType,
    'INFO',
  );
});

it('platform action result local reactions compose without duplicate local defaults', async () => {
  const result = withPlatformActionResultReactions(
    {
      message: '已处理',
      reactions: [platformActionResultReactions.refreshList({ source: 'local-page' })],
    },
    [
      platformActionResultReactions.refreshList({ source: 'local' }),
      platformActionResultReactions.closeEditor(),
    ],
  );

  assert.deepEqual(resolvePlatformActionResult(result).reactions, [
    { type: platformActionResultReactionTypes.refreshList, payload: { source: 'local-page' } },
    { type: platformActionResultReactionTypes.closeEditor },
  ]);

  const handled: string[] = [];
  await handlePlatformActionSuccess(result, {
    reactionHandlers: createPlatformActionResultReactionHandlers({
      refreshList: (reaction) => {
        handled.push(`${reaction.type}:${reaction.payload?.source}`);
      },
      closeEditor: (reaction) => {
        handled.push(reaction.type);
      },
    }),
  });

  assert.deepEqual(handled, ['refresh-list:local-page', 'close-editor']);
});

it('platform action result reactions keep same type for different records', () => {
  const result = withPlatformActionResultReactions(
    {
      message: '已处理',
      reactions: [
        platformActionResultReactions.refreshDetail({ moduleAlias: 'iam.employee', recordId: 'emp-1' }),
      ],
    },
    [platformActionResultReactions.refreshDetail({ moduleAlias: 'iam.employee', recordId: 'emp-2' })],
  );

  assert.deepEqual(resolvePlatformActionResult(result).reactions, [
    {
      type: platformActionResultReactionTypes.refreshDetail,
      payload: { moduleAlias: 'iam.employee', recordId: 'emp-1' },
    },
    {
      type: platformActionResultReactionTypes.refreshDetail,
      payload: { moduleAlias: 'iam.employee', recordId: 'emp-2' },
    },
  ]);
});
