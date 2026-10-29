import { reactive } from 'vue';
import { expect, it, vi } from 'vitest';
import { loadOptionFieldItems } from '@/platform-components/optionFieldOptionCache';
import type { ModuleContext } from '@muyun/web-core';

it('scopes cached option requests by the optional dynamic entity alias', async () => {
  const request = vi.fn().mockResolvedValue([{ code: 'ATTENDED', title: '已参加', enabled: true }]);
  const context = {
    moduleAlias: 'education.exam',
    http: { request },
  } as unknown as ModuleContext<unknown>;

  await loadOptionFieldItems(context, 'attendanceStatus', 'exam_participant');
  await loadOptionFieldItems(context, 'attendanceStatus', 'exam_participant');
  await loadOptionFieldItems(context, 'attendanceStatus', 'exam');

  expect(request).toHaveBeenCalledTimes(2);
  expect(request).toHaveBeenNthCalledWith(1, {
    path: '/platform.module/education.exam/fields/attendanceStatus/options',
    query: { enabledOnly: false, entityAlias: 'exam_participant' },
  });
  expect(request).toHaveBeenNthCalledWith(2, {
    path: '/platform.module/education.exam/fields/attendanceStatus/options',
    query: { enabledOnly: false, entityAlias: 'exam' },
  });
});

it('does not let an obsolete failed read evict a freshly published option resource', async () => {
  let rejectOld!: (reason: Error) => void;
  const old = new Promise<never>((_, reject) => {
    rejectOld = reject;
  });
  const request = vi
    .fn()
    .mockReturnValueOnce(old)
    .mockResolvedValue([{ code: 'ACTIVE', title: 'Updated title', enabled: true }]);
  const context = { moduleAlias: 'sales.order', http: { request } } as unknown as ModuleContext<unknown>;
  const first = loadOptionFieldItems(context, 'state');
  const observedFailure = expect(first).rejects.toThrow('obsolete');
  const updated = await loadOptionFieldItems(context, 'state', undefined, context.moduleAlias, true);
  rejectOld(new Error('obsolete'));
  await observedFailure;
  expect(await loadOptionFieldItems(context, 'state')).toBe(updated);
  expect(request).toHaveBeenCalledTimes(2);
});

it('withdraws stale candidates during refresh and failure and publishes only the newest resource', async () => {
  const { optionFieldState } = await import('@/platform-components/optionFieldOptionCache');
  let finishOld!: (items: Array<{ code: string; title: string; enabled: boolean }>) => void;
  const request = vi
    .fn()
    .mockResolvedValueOnce([{ code: 'old', title: '旧选项', enabled: true }])
    .mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOld = resolve;
        }),
    )
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce([{ code: 'new', title: '新选项', enabled: false }]);
  const context = { moduleAlias: 'sales.order', http: { request } } as unknown as ModuleContext<unknown>;
  const state = optionFieldState(context, 'state');
  await loadOptionFieldItems(context, 'state');
  expect(state.items[0]?.code).toBe('old');
  expect(optionFieldState(reactive(context), 'state')).toBe(state);
  const stale = loadOptionFieldItems(context, 'state', undefined, context.moduleAlias, true);
  expect(state.status).toBe('loading');
  expect(state.items).toEqual([]);
  await expect(loadOptionFieldItems(context, 'state', undefined, context.moduleAlias, true)).rejects.toThrow(
    'offline',
  );
  expect(state.status).toBe('error');
  expect(state.items).toEqual([]);
  await loadOptionFieldItems(context, 'state');
  finishOld([{ code: 'stale', title: '迟到选项', enabled: true }]);
  await stale;
  expect(state.status).toBe('ready');
  expect(state.items).toEqual([{ code: 'new', title: '新选项', enabled: false }]);
  expect(optionFieldState({ ...context }, 'state').items).toEqual([]);
  expect(optionFieldState(context, 'state', 'detail').items).toEqual([]);
});

it('settles shared candidates after success or failure and cancels only the waiter', async () => {
  const { settleOptionFieldItems, optionFieldState } =
    await import('@/platform-components/optionFieldOptionCache');
  let resolve!: (items: unknown[]) => void;
  let reject!: (error: Error) => void;
  const request = vi.fn(
    () =>
      new Promise((yes, no) => {
        resolve = yes;
        reject = no;
      }),
  );
  const context = { moduleAlias: 'demo.record', http: { request } } as unknown as ModuleContext<unknown>;
  const loading = loadOptionFieldItems(context, 'state');
  let settled = false;
  const controller = new AbortController();
  const waiting = settleOptionFieldItems(context, ['state'], controller.signal).then(() => {
    settled = true;
  });
  await Promise.resolve();
  expect(settled).toBe(false);
  const cancelled = new AbortController();
  const abandoned = settleOptionFieldItems(context, ['state'], cancelled.signal);
  cancelled.abort();
  await expect(abandoned).rejects.toMatchObject({ name: 'AbortError' });
  expect(optionFieldState(context, 'state').status).toBe('loading');
  resolve([]);
  await loading;
  await waiting;
  expect(settled).toBe(true);
  const failed = loadOptionFieldItems(context, 'state', undefined, context.moduleAlias, true);
  const failure = expect(failed).rejects.toThrow('offline');
  const failedWait = settleOptionFieldItems(context, ['state'], controller.signal);
  await Promise.resolve();
  reject(new Error('offline'));
  await failure;
  await failedWait;
  expect(optionFieldState(context, 'state').status).toBe('error');
});
