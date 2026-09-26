import { describe, expect, it, vi } from 'vitest';
import {
  createAssistantOperationConfirmation,
  AssistantOperationRejectedError,
  type AssistantOperationProposal,
} from '../../src/web-core/assistantConfirmation';

function fixture() {
  const receipt = { title: '已保存', lines: ['记录 42'] };
  const proposal: AssistantOperationProposal = {
    presentation: { title: '保存确认', lines: ['名称：订单'] },
    expiresAt: 100,
    isCurrent: vi.fn(() => true),
    execute: vi.fn(async () => receipt),
    lookup: vi.fn(async () => receipt),
  };
  const scope = vi.fn(() => true);
  return { proposal, scope, confirmation: createAssistantOperationConfirmation(proposal, scope, () => 10) };
}

describe('human-confirmed operation', () => {
  it('does not execute on proposal creation and coalesces duplicate confirmation', async () => {
    const { proposal, confirmation } = fixture();
    expect(proposal.execute).not.toHaveBeenCalled();
    await Promise.all([confirmation.confirm(), confirmation.confirm()]);
    await confirmation.confirm();
    expect(proposal.execute).toHaveBeenCalledTimes(1);
    expect(confirmation.state).toBe('succeeded');
    expect(confirmation.result?.title).toBe('已保存');
  });

  it('invalidates changed drafts, expired proposals and changed identity before execution', async () => {
    for (const invalidate of [
      (f: ReturnType<typeof fixture>) => {
        f.proposal.isCurrent = () => false;
      },
      (f: ReturnType<typeof fixture>) => {
        f.proposal.expiresAt = 5;
      },
      (f: ReturnType<typeof fixture>) => {
        f.scope.mockReturnValue(false);
      },
    ]) {
      const f = fixture();
      invalidate(f);
      await f.confirmation.confirm();
      expect(f.confirmation.state).toBe('expired');
      expect(f.proposal.execute).not.toHaveBeenCalled();
    }
  });

  it('queries an unknown result without issuing another mutation', async () => {
    const { proposal, confirmation } = fixture();
    proposal.execute = vi.fn(async () => {
      throw new Error('response lost after commit');
    });
    await confirmation.confirm();
    expect(confirmation.state).toBe('unknown');
    await confirmation.confirm();
    await confirmation.check();
    expect(confirmation.state).toBe('succeeded');
    expect(proposal.execute).toHaveBeenCalledTimes(1);
    expect(proposal.lookup).toHaveBeenCalledTimes(1);
  });

  it('keeps a missing receipt unknown and does not query in another identity scope', async () => {
    const { proposal, scope, confirmation } = fixture();
    proposal.execute = async () => {
      throw new Error('disconnected');
    };
    proposal.lookup = vi.fn(async () => undefined);
    await confirmation.confirm();
    await confirmation.check();
    expect(confirmation.state).toBe('unknown');
    scope.mockReturnValue(false);
    await confirmation.check();
    expect(proposal.lookup).toHaveBeenCalledTimes(1);
  });

  it('cancels only unsubmitted proposals and retains the result of an in-flight write', async () => {
    const cancelled = fixture();
    cancelled.confirmation.cancel();
    await cancelled.confirmation.confirm();
    expect(cancelled.proposal.execute).not.toHaveBeenCalled();
    const active = fixture();
    const pending = active.confirmation.confirm();
    active.confirmation.cancel();
    await pending;
    expect(active.confirmation.state).toBe('succeeded');
  });
});

it('distinguishes a proven rejection from an unknown transport result', async () => {
  const { proposal, confirmation } = fixture();
  proposal.execute = async () => {
    throw new AssistantOperationRejectedError('字段校验未通过');
  };
  await confirmation.confirm();
  expect(confirmation.state).toBe('rejected');
  expect(confirmation.result?.lines).toEqual(['字段校验未通过']);
  await confirmation.check();
  expect(proposal.lookup).not.toHaveBeenCalled();
});

it('retries a proven rejection with the captured proposal and coalesces repeated clicks', async () => {
  const { proposal, confirmation } = fixture();
  proposal.execute = vi
    .fn()
    .mockRejectedValueOnce(new AssistantOperationRejectedError('服务入口暂不可用'))
    .mockResolvedValue({ title: '已保存', lines: [] });
  await confirmation.confirm();
  expect(confirmation.state).toBe('rejected');
  await Promise.all([confirmation.confirm(), confirmation.confirm()]);
  expect(proposal.execute).toHaveBeenCalledTimes(2);
  expect(confirmation.state).toBe('succeeded');
});

it('expires rejected proposals after edits and never retries a rejected receipt lookup', async () => {
  const first = fixture();
  first.proposal.execute = vi.fn().mockRejectedValue(new AssistantOperationRejectedError('校验失败'));
  await first.confirmation.confirm();
  first.proposal.isCurrent = () => false;
  await first.confirmation.confirm();
  expect(first.confirmation.state).toBe('expired');
  expect(first.proposal.execute).toHaveBeenCalledTimes(1);

  const second = fixture();
  second.proposal.execute = vi.fn().mockRejectedValue(new Error('response lost'));
  second.proposal.lookup = vi.fn().mockRejectedValue(new AssistantOperationRejectedError('权限不足'));
  await second.confirmation.confirm();
  await second.confirmation.check();
  expect(second.confirmation.state).toBe('unknown');
  await second.confirmation.confirm();
  expect(second.proposal.execute).toHaveBeenCalledTimes(1);
});

it('offers a trusted continuation once, only after a known receipt in the original scope', async () => {
  const f = fixture();
  let current = true;
  f.proposal.continuation = { message: 'read task', isCurrent: () => current };
  const confirmation = createAssistantOperationConfirmation(f.proposal, f.scope, () => 10);
  expect(confirmation.takeContinuation()).toBeUndefined();
  vi.mocked(f.proposal.execute).mockRejectedValueOnce(new Error('lost response'));
  await confirmation.confirm();
  expect(confirmation.takeContinuation()).toBeUndefined();
  await confirmation.check();
  current = false;
  expect(confirmation.takeContinuation()).toBeUndefined();
  current = true;
  f.scope.mockReturnValue(false);
  expect(confirmation.takeContinuation()).toBeUndefined();
  f.scope.mockReturnValue(true);
  expect(confirmation.takeContinuation()).toBe('read task');
  expect(confirmation.takeContinuation()).toBeUndefined();
  expect(f.proposal.execute).toHaveBeenCalledOnce();
});
