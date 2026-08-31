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
