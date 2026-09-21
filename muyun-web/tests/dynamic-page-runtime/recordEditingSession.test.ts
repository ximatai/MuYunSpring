import { describe, it, expect, vi } from 'vitest';
import type { ModuleContext } from '@muyun/web-core';
import { useRecordEditingSession } from '@/dynamic-page-runtime/composables/useRecordEditingSession';

function setup(view: ReturnType<typeof vi.fn>) {
  const detail = { beginLoad: vi.fn(), resolveLoad: vi.fn(), failLoad: vi.fn(), finishLoad: vi.fn() };
  const onLoaded = vi.fn();
  const session = useRecordEditingSession(
    { crud: { view } } as unknown as ModuleContext<{ id: string }>,
    detail,
    onLoaded,
  );
  return { detail, onLoaded, session };
}
describe('record detail reload after an action', () => {
  it('discards an earlier response when another record is selected', async () => {
    let resolveOld!: (record: { id: string }) => void;
    const { detail, session } = setup(
      vi
        .fn()
        .mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              resolveOld = resolve;
            }),
        )
        .mockResolvedValueOnce({ id: 'b' }),
    );
    const pending = session.openRecord({ id: 'a' }, 'view');
    await session.openRecord({ id: 'b' }, 'view');
    resolveOld({ id: 'a' });
    await pending;
    expect(detail.resolveLoad.mock.calls).toEqual([[{ id: 'b' }]]);
  });
  it('does not clear a new selection when an old request is denied', async () => {
    let rejectOld!: (cause: Error) => void;
    const { detail, session } = setup(
      vi
        .fn()
        .mockImplementationOnce(
          () =>
            new Promise((_, reject) => {
              rejectOld = reject;
            }),
        )
        .mockResolvedValueOnce({ id: 'b' }),
    );
    const onError = vi.fn();
    const pending = session.openRecord({ id: 'a' }, 'view', {}, false, onError);
    await session.openRecord({ id: 'b' }, 'view');
    rejectOld(new Error('denied'));
    await pending;
    expect(onError).not.toHaveBeenCalled();
    expect(detail.failLoad).not.toHaveBeenCalled();
  });
  it('exposes current load failures without replacing the selected record', async () => {
    const error = new Error('network unavailable');
    const { detail, session } = setup(vi.fn().mockRejectedValue(error));
    const onError = vi.fn();
    await session.openRecord({ id: 'a' }, 'view', {}, false, onError);
    expect(detail.failLoad).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith(error);
    expect(detail.resolveLoad).not.toHaveBeenCalled();
    expect(detail.finishLoad).toHaveBeenCalledOnce();
  });

  it('commits a prepared record through the same detail lifecycle without another request', () => {
    const view = vi.fn();
    const { detail, onLoaded, session } = setup(view);

    session.commitLoadedRecord({ id: 'prepared' }, 'edit', { cancelDestination: 'close' });

    expect(view).not.toHaveBeenCalled();
    expect(detail.beginLoad).toHaveBeenCalledWith({ id: 'prepared' }, 'edit', { cancelDestination: 'close' });
    expect(detail.resolveLoad).toHaveBeenCalledWith({ id: 'prepared' });
    expect(onLoaded).toHaveBeenCalledOnce();
    expect(detail.finishLoad).toHaveBeenCalledOnce();
  });

  it('settles only after the latest serialized detail load completes', async () => {
    let resolveFirst!: (record: { id: string }) => void;
    let resolveLatest!: (record: { id: string }) => void;
    const { session } = setup(
      vi
        .fn()
        .mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              resolveFirst = resolve;
            }),
        )
        .mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              resolveLatest = resolve;
            }),
        ),
    );

    const first = session.openRecord({ id: 'a' }, 'view');
    const latest = session.openRecord({ id: 'b' }, 'view');
    let settled = false;
    const settlement = session.settlePendingRecord().then(() => {
      settled = true;
    });

    resolveFirst({ id: 'a' });
    await first;
    expect(settled).toBe(false);
    resolveLatest({ id: 'b' });
    await latest;
    await settlement;
    expect(settled).toBe(true);
  });

  it('stops waiting promptly when assistant settlement is cancelled', async () => {
    let resolveRequest!: (record: { id: string }) => void;
    const { session } = setup(
      vi.fn(
        () =>
          new Promise<{ id: string }>((resolve) => {
            resolveRequest = resolve;
          }),
      ),
    );
    const pending = session.openRecord({ id: 'a' }, 'view');
    const controller = new AbortController();
    const settlement = session.settlePendingRecord(controller.signal);

    controller.abort();

    await expect(settlement).rejects.toMatchObject({ name: 'AbortError' });
    resolveRequest({ id: 'a' });
    await pending;
  });
});
