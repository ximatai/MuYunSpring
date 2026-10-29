import { describe, expect, it, vi } from 'vitest';
import { createBusinessNotificationSession } from '@muyun/web-core';
import type { WebBusinessNotification } from '@muyun/web-contracts';

function notice(id: string, code = 'a', title = id): WebBusinessNotification {
  return { id, code, title, content: title, actions: [], dismissible: true };
}
function deferred() {
  let resolve!: (value: WebBusinessNotification[]) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<WebBusinessNotification[]>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
function fixture() {
  const isCurrent = vi.fn(() => true);
  const onChange = vi.fn();
  const onRecoveryError = vi.fn();
  const session = createBusinessNotificationSession({
    isCurrent,
    onChange,
    onRecoveryError,
    now: () => 1000,
  });
  return { session, isCurrent, onChange, onRecoveryError };
}

describe('business notification session recovery', () => {
  it('reconciles two independent sources without replacing live unowned notifications', async () => {
    const { session } = fixture();
    session.receive(notice('unowned', 'c'));
    session.receive(notice('old-a'));
    session.receive(notice('old-b', 'b'));
    session.register({ id: 'a', owns: (n) => n.code === 'a', load: async () => [notice('new-a')] });
    session.register({ id: 'b', owns: (n) => n.code === 'b', load: async () => [notice('new-b', 'b')] });
    await session.recover();
    expect(
      session
        .snapshot()
        .map((n) => n.id)
        .sort(),
    ).toEqual(['new-a', 'new-b', 'unowned']);
  });

  it('coalesces requests per source and preserves realtime updates while applying the remaining snapshot', async () => {
    const { session } = fixture();
    const pending = deferred();
    const load = vi.fn(() => pending.promise);
    session.register({ id: 'a', owns: (n) => n.code === 'a', load });
    session.receive(notice('removed'));
    const one = session.recover('a');
    const two = session.recover('a');
    await Promise.resolve();
    session.receive(notice('live', 'a', 'new value'));
    pending.resolve([notice('live', 'a', 'stale value'), notice('offline')]);
    await Promise.all([one, two]);
    expect(load).toHaveBeenCalledOnce();
    expect(session.snapshot().map((n) => [n.id, n.title])).toEqual([
      ['offline', 'offline'],
      ['live', 'new value'],
    ]);
  });

  it('does not resurrect dismissed or expired notices from an older response', async () => {
    const { session } = fixture();
    const pending = deferred();
    session.register({ id: 'a', owns: (n) => n.code === 'a', load: () => pending.promise });
    session.receive(notice('dismissed'));
    const loading = session.recover();
    await Promise.resolve();
    session.dismiss('dismissed');
    session.receive({ ...notice('withdrawn'), expiresAt: new Date(999).toISOString() });
    pending.resolve([
      notice('dismissed'),
      notice('withdrawn'),
      { ...notice('expired'), expiresAt: 'invalid' },
    ]);
    await loading;
    expect(session.snapshot()).toEqual([]);
    await session.recover();
    expect(session.snapshot().map((n) => n.id)).toEqual(['withdrawn']);
    // Dismiss lasts for this session; an authoritative later snapshot may restore a withdrawn fact.
  });

  it('preserves every source on failed or invalid recovery', async () => {
    const { session, onRecoveryError } = fixture();
    const load = vi
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce([notice('intruder', 'b')])
      .mockResolvedValueOnce([notice('same'), notice('same')]);
    session.register({ id: 'a', owns: (n) => n.code === 'a', load });
    session.register({ id: 'b', owns: (n) => n.code === 'b', load: async () => [] });
    session.receive(notice('a'));
    session.receive(notice('b', 'b'));
    for (let i = 0; i < 3; i++) {
      await session.recover('a');
      expect(session.snapshot().map((n) => n.id)).toEqual(['a', 'b']);
    }
    expect(onRecoveryError).toHaveBeenCalledTimes(3);
  });

  it('rejects overlapping ownership and colliding identities without partial replacement', async () => {
    const { session, onRecoveryError } = fixture();
    session.register({ id: 'a', owns: (n) => n.code === 'a', load: async () => [notice('same')] });
    session.receive(notice('owned'));
    expect(() => session.register({ id: 'overlap', owns: () => true, load: async () => [] })).toThrow();
    session.receive(notice('same', 'b'));
    await session.recover();
    expect(onRecoveryError).toHaveBeenCalledOnce();
    expect(session.snapshot().map((n) => n.code)).toEqual(['a', 'b']);
    expect(() => session.receive(notice('same', 'a'))).toThrow();
  });

  it('cancels on dispose and ignores uncooperative late responses and live arrivals', async () => {
    const { session, onChange } = fixture();
    const pending = deferred();
    let signal!: AbortSignal;
    session.register({
      id: 'a',
      owns: () => true,
      load: (value) => {
        signal = value;
        return pending.promise;
      },
    });
    session.receive(notice('before'));
    const loading = session.recover();
    await Promise.resolve();
    session.dispose();
    expect(signal.aborted).toBe(true);
    pending.resolve([notice('late')]);
    await loading;
    session.receive(notice('live'));
    await session.recover();
    session.dispose();
    expect(session.snapshot()).toEqual([]);
    expect(onChange).toHaveBeenLastCalledWith([]);
    expect(onChange).toHaveBeenCalledTimes(2);
  });

  it('does not publish across an identity change even before connection disposal', async () => {
    const { session, isCurrent, onChange } = fixture();
    const pending = deferred();
    session.register({ id: 'a', owns: () => true, load: () => pending.promise });
    const loading = session.recover();
    await Promise.resolve();
    isCurrent.mockReturnValue(false);
    pending.resolve([notice('private')]);
    await loading;
    session.receive(notice('late'));
    expect(onChange).not.toHaveBeenCalled();
    expect(session.snapshot()).toEqual([]);
  });

  it('starts a fresh recovery after reconnect instead of reusing a pre-disconnect query', async () => {
    const { session } = fixture();
    const old = deferred();
    const fresh = deferred();
    const signals: AbortSignal[] = [];
    const load = vi.fn((signal: AbortSignal) => {
      signals.push(signal);
      return signals.length === 1 ? old.promise : fresh.promise;
    });
    session.register({ id: 'a', owns: () => true, load });
    const first = session.recover();
    await Promise.resolve();
    session.cancelRecovery();
    const second = session.recover();
    await Promise.resolve();
    fresh.resolve([notice('fresh')]);
    await second;
    old.resolve([notice('stale')]);
    await first;
    expect(signals[0]!.aborted).toBe(true);
    expect(load).toHaveBeenCalledTimes(2);
    expect(session.snapshot().map((n) => n.id)).toEqual(['fresh']);
  });

  it('unregisters only the owned subset and never applies an old registration to its replacement', async () => {
    const { session } = fixture();
    const pending = deferred();
    const unregister = session.register({
      id: 'a',
      owns: (n) => n.code === 'a',
      load: () => pending.promise,
    });
    session.receive(notice('old'));
    session.receive(notice('other', 'b'));
    const loading = session.recover();
    await Promise.resolve();
    unregister();
    session.register({ id: 'a', owns: (n) => n.code === 'a', load: async () => [notice('new')] });
    await session.recover();
    pending.resolve([notice('late')]);
    await loading;
    unregister();
    expect(session.snapshot().map((n) => n.id)).toEqual(['other', 'new']);
  });

  it('isolates source, observer and returned snapshots from internal state', () => {
    const { session, onChange } = fixture();
    const value = notice('id');
    session.receive(value);
    value.title = 'changed';
    session.snapshot()[0]!.title = 'changed again';
    onChange.mock.calls[0]![0][0].title = 'observer mutation';
    expect(session.snapshot()[0]!.title).toBe('id');
  });
});
