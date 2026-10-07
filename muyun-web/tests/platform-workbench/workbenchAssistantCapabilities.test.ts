import { describe, expect, it, vi } from 'vitest';
import type { MenuTreeNode } from '@muyun/web-contracts';
import {
  createWorkbenchAssistantCapabilities,
  workbenchAssistantMenuCatalog,
} from '@/platform-workbench/workbenchAssistantCapabilities';

function menuTree(): MenuTreeNode[] {
  return [
    {
      record: { id: 'root', title: '业务管理', schemeId: 'default' },
      children: [
        {
          record: {
            id: 'daily-report',
            title: '日报填报',
            schemeId: 'default',
            moduleAlias: 'work.daily_report',
            moduleDescription: '登记每天的工作进展',
            entryType: 'module',
            openMode: 'tab',
          },
          children: [],
        },
      ],
    },
  ] as MenuTreeNode[];
}

describe('workbench assistant capabilities', () => {
  it('projects the same live openable menu facts with bounded, explicit incompleteness', async () => {
    const tree = menuTree();
    const find = createWorkbenchAssistantCapabilities(
      () => tree,
      () => true,
    )[0]!;
    expect(workbenchAssistantMenuCatalog(tree).items).toEqual(
      await find.execute(find.parseInput({ query: '业务管理' }), executionContext()),
    );
    expect(workbenchAssistantMenuCatalog([]).items).toEqual([]);
    const entry = tree[0]!.children![0]!;
    const many = Array.from({ length: 13 }, (_, i) => ({
      ...entry,
      record: { ...entry.record, id: `menu-${i}` },
    }));
    const first = workbenchAssistantMenuCatalog(many);
    expect(first.items).toHaveLength(10);
    expect(first.page).toMatchObject({ total: 13, nextOffset: 10 });
    const oversized = workbenchAssistantMenuCatalog([
      { ...entry, record: { ...entry.record, title: '长标题'.repeat(2000) } },
      entry,
    ]);
    expect(oversized.items).toHaveLength(1);
    expect(oversized.page.oversizedIndexes).toEqual([0]);
    expect(oversized.page.note).toContain('按名称查找');
    expect(JSON.stringify(oversized.items).length).toBeLessThan(4000);
  });
  it('presents the actual opened entry as navigation, not a business save', async () => {
    const open = createWorkbenchAssistantCapabilities(menuTree, () => true)[1]!;
    const result = await open.execute(open.parseInput({ menuId: 'daily-report' }), executionContext());
    expect(open.present?.(result)).toEqual({
      title: '已打开日报填报',
      lines: ['仅切换页面，未修改或保存业务数据。'],
    });
  });
  it('finds only entries from the current visible tree with case-insensitive matching', async () => {
    const capabilities = createWorkbenchAssistantCapabilities(menuTree, () => true);
    const find = capabilities.find(({ descriptor }) => descriptor.code === 'workbench.find-menu')!;

    const result = await find.execute(find.parseInput({ query: 'DAILY_report' }), executionContext());

    expect(result).toEqual([
      expect.objectContaining({
        menuId: 'daily-report',
        schemeId: 'default',
        moduleAlias: 'work.daily_report',
      }),
    ]);
  });

  it('discovers a visible module by its optional purpose without loading detailed capabilities', async () => {
    const find = createWorkbenchAssistantCapabilities(menuTree, () => true)[0]!;
    expect(await find.execute(find.parseInput({ query: '工作进展' }), executionContext())).toEqual([
      expect.objectContaining({ menuId: 'daily-report', description: '登记每天的工作进展' }),
    ]);
    const unavailable = createWorkbenchAssistantCapabilities(
      () => [],
      () => true,
    )[0]!;
    expect(
      await unavailable.execute(unavailable.parseInput({ query: '工作进展' }), executionContext()),
    ).toEqual([]);
  });

  it('rechecks menu visibility immediately before opening', async () => {
    let menus = menuTree();
    const openMenu = vi.fn(() => true);
    const open = createWorkbenchAssistantCapabilities(() => menus, openMenu).find(
      ({ descriptor }) => descriptor.code === 'workbench.open-menu',
    )!;
    const input = open.parseInput({ menuId: 'daily-report' });

    menus = [];

    await expect(open.execute(input, executionContext())).rejects.toThrow('Visible menu is unavailable');
    expect(openMenu).not.toHaveBeenCalled();
  });

  it('does not return visible grouping nodes that cannot be opened', async () => {
    const find = createWorkbenchAssistantCapabilities(menuTree, () => true).find(
      ({ descriptor }) => descriptor.code === 'workbench.find-menu',
    )!;

    const result = (await find.execute(find.parseInput({ query: '业务管理' }), executionContext())) as Array<{
      menuId: string;
    }>;
    expect(result).not.toContainEqual(expect.objectContaining({ menuId: 'root' }));
  });
});

function executionContext() {
  return {
    signal: new AbortController().signal,
    isCurrent: () => true,
    commitInternalState<T>(commit: () => T) {
      return commit();
    },
    applyEffect<T>(effect: () => T) {
      return effect();
    },
  };
}
