import { describe, expect, it, vi } from 'vitest';
import type { MenuTreeNode } from '@muyun/web-contracts';
import { createWorkbenchAssistantCapabilities } from '@/platform-workbench/workbenchAssistantCapabilities';

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
  it('finds only entries from the current visible tree with case-insensitive matching', async () => {
    const capabilities = createWorkbenchAssistantCapabilities(menuTree, () => true);
    const find = capabilities.find(({ descriptor }) => descriptor.code === 'workbench.find-menu')!;

    const result = await find.execute(find.parseInput({ query: 'DAILY_report' }), executionContext());

    expect(result).toEqual([
      expect.objectContaining({ menuId: 'daily-report', moduleAlias: 'work.daily_report' }),
    ]);
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
    applyEffect<T>(effect: () => T) {
      return effect();
    },
  };
}
