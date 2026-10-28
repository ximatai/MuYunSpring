import { describe, expect, it, vi } from 'vitest';
import {
  createOperationConfirmation,
  restoreOperationConfirmation,
  OperationRejectedError,
  type OperationProposal,
} from '@muyun/web-core';
import type { OperationResult } from '@muyun/web-contracts';

const presentation = { title: 'Business command', lines: ['Captured request 1'] };
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function fixture() {
  const scope = vi.fn(() => true);
  const proposal: OperationProposal<OperationResult> = {
    presentation,
    expiresAt: 100,
    isCurrent: () => true,
    execute: vi.fn(async () => presentation),
    lookup: vi.fn(async () => presentation),
  };
  return { scope, proposal, create: () => createOperationConfirmation(proposal, scope, () => 1) };
}

describe('business command receipts', () => {
  it('keeps accepted work queryable without re-execution, including after proposal expiry', async () => {
    const f = fixture();
    f.proposal.execute = vi.fn(async () => ({ status: 'processing' as const, presentation }));
    f.proposal.lookup = vi.fn(async () => ({ status: 'succeeded' as const, presentation }));
    const confirmation = f.create();
    await Promise.all([confirmation.confirm(), confirmation.confirm()]);
    expect(confirmation.state).toBe('processing');
    confirmation.cancel();
    f.proposal.expiresAt = 0;
    await confirmation.confirm();
    await Promise.all([confirmation.check(), confirmation.check()]);
    expect(confirmation.state).toBe('succeeded');
    expect(f.proposal.execute).toHaveBeenCalledOnce();
    expect(f.proposal.lookup).toHaveBeenCalledOnce();
  });

  it.each(['execute', 'lookup'] as const)(
    'does not replay a durable failure observed by %s',
    async (path) => {
      const f = fixture();
      f.proposal.execute = vi.fn(async () =>
        path === 'execute'
          ? { status: 'failed' as const, presentation }
          : { status: 'processing' as const, presentation },
      );
      f.proposal.lookup = vi.fn(async () => ({ status: 'failed' as const, presentation }));
      const confirmation = f.create();
      await confirmation.confirm();
      if (path === 'lookup') await confirmation.check();
      expect(confirmation.state).toBe('failed');
      await confirmation.confirm();
      await confirmation.check();
      expect(f.proposal.execute).toHaveBeenCalledOnce();
      expect(confirmation.result).toEqual(presentation);
    },
  );

  it.each(['execute', 'lookup'] as const)('discards a late %s result after scope changes', async (path) => {
    const f = fixture();
    const pending = deferred<OperationResult>();
    f.proposal.execute = vi.fn(async () =>
      path === 'execute' ? pending.promise : { status: 'processing' as const, presentation },
    );
    f.proposal.lookup = vi.fn(() => pending.promise);
    const confirmation = f.create();
    let running = confirmation.confirm();
    if (path === 'lookup') {
      await running;
      running = confirmation.check();
    }
    await Promise.resolve();
    f.scope.mockReturnValue(false);
    pending.resolve({ status: 'succeeded' as const, presentation });
    await running;
    expect(confirmation.state).toBe('unknown');
    expect(confirmation.result).toBeUndefined();
    await confirmation.confirm();
    expect(f.proposal.execute).toHaveBeenCalledOnce();
  });

  it('does not make a late rejection retryable under another identity', async () => {
    const f = fixture();
    const pending = deferred<OperationResult>();
    f.proposal.execute = () => pending.promise;
    const confirmation = f.create();
    const running = confirmation.confirm();
    await Promise.resolve();
    f.scope.mockReturnValue(false);
    pending.reject(new OperationRejectedError('old identity details'));
    await running;
    expect(confirmation.state).toBe('unknown');
    expect(confirmation.result).toBeUndefined();
  });

  it('restores only read authority and isolates receipt snapshots', async () => {
    const result: OperationResult = {
      status: 'processing' as const,
      presentation: structuredClone(presentation),
    };
    const lookup = vi.fn(async () => result);
    const confirmation = restoreOperationConfirmation({ presentation, lookup }, () => true);
    await confirmation.confirm();
    confirmation.cancel();
    expect(lookup).not.toHaveBeenCalled();
    await Promise.all([confirmation.check(), confirmation.check()]);
    expect(confirmation.state).toBe('processing');
    result.presentation.lines.push('later mutation');
    confirmation.result!.lines.push('consumer mutation');
    expect(confirmation.result?.lines).toEqual(['Captured request 1']);
    await confirmation.confirm();
    expect(lookup).toHaveBeenCalledOnce();
  });

  it('a missing receipt or failed lookup never becomes a new submit permission', async () => {
    const lookup = vi
      .fn()
      .mockResolvedValueOnce(undefined)
      .mockRejectedValueOnce(new OperationRejectedError('denied'));
    const confirmation = restoreOperationConfirmation({ presentation, lookup }, () => true);
    for (let i = 0; i < 2; i++) {
      await confirmation.check();
      expect(confirmation.state).toBe('unknown');
      await confirmation.confirm();
    }
    expect(lookup).toHaveBeenCalledTimes(2);
  });

  it('hides an already loaded receipt when the identity scope is no longer current', async () => {
    const f = fixture();
    const confirmation = f.create();
    await confirmation.confirm();
    f.scope.mockReturnValue(false);
    expect(confirmation.result).toBeUndefined();
  });
});

it('retains a committed receipt across a surface rebuild but blocks it after identity changes', async () => {
  let surfaceCurrent = true;
  let identityCurrent = true;
  const execute = vi.fn(async () => {
    surfaceCurrent = false;
    return { status: 'processing' as const, presentation };
  });
  const confirmation = createOperationConfirmation(
    { presentation, expiresAt: 100, isCurrent: () => true, execute, lookup: async () => presentation },
    () => surfaceCurrent,
    () => 1,
    undefined,
    () => identityCurrent,
  );
  await confirmation.confirm();
  expect(confirmation.state).toBe('processing');
  await confirmation.check();
  expect(confirmation.state).toBe('succeeded');
  expect(confirmation.result).toEqual(presentation);
  identityCurrent = false;
  expect(confirmation.result).toBeUndefined();
  await confirmation.confirm();
  expect(execute).toHaveBeenCalledOnce();
});
