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
