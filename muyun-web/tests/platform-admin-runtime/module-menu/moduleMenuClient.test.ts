import { describe, expect, it, vi } from 'vitest';
import {
  createModuleMenuClient,
  menuDirectoryOptions,
  menuPlacements,
} from '@/platform-admin-runtime/module-menu/moduleMenuClient';
import type { MenuTreeNode } from '@/web-contracts';

describe('module menu maintenance', () => {
  it('keeps scheme context on both tree reads and standard creation', async () => {
    const request = vi.fn().mockResolvedValueOnce({ records: [] }).mockResolvedValueOnce({ id: 'created' });
    const client = createModuleMenuClient({ request });
    await client.tree('scheme-a');
    await client.insert('scheme-a', { title: '客户', moduleAlias: 'crm.customer' } as never);
    expect(request.mock.calls.map(([call]) => call.headers)).toEqual([
      { 'X-MuYun-Page-Context': '{"scheme":"scheme-a"}' },
      { 'X-MuYun-Page-Context': '{"scheme":"scheme-a"}' },
    ]);
    expect(request.mock.calls[1][0].path).toBe('/platform.menu/insert');
    expect(request.mock.calls[1][0].body).toEqual({ title: '客户', moduleAlias: 'crm.customer' });
  });

  it('loads all scheme pages instead of silently hiding later choices', async () => {
    const request = vi
      .fn()
      .mockResolvedValueOnce({ records: [{ id: 'a' }], total: 2 })
      .mockResolvedValueOnce({ records: [{ id: 'b' }], total: 2 });
    expect(await createModuleMenuClient({ request }).schemes()).toEqual([{ id: 'a' }, { id: 'b' }]);
    expect(request.mock.calls[1][0].body.page.pageNum).toBe(2);
  });

  it('keeps complete paths and excludes disabled ancestors and business entries as destinations', () => {
    const tree = [
      {
        record: { schemeId: 'scheme', id: 'a', title: '业务', enabled: false },
        children: [{ record: { schemeId: 'scheme', id: 'b', title: '目录' }, children: [] }],
      },
      { record: { schemeId: 'scheme', id: 'c', title: '客户', moduleAlias: 'crm.customer' }, children: [] },
    ] as MenuTreeNode[];
    expect(menuPlacements(tree, '方案').map((item) => item.path)).toEqual([
      '方案 / 业务',
      '方案 / 业务 / 目录',
      '方案 / 客户',
    ]);
    const directories = menuDirectoryOptions(tree);
    expect(directories[0].disabled).toBe(true);
    expect(directories[0].children![0].disabled).toBe(true);
    expect(directories[1].disabled).toBe(true);
  });
});
