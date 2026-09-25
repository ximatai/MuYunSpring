import { AssistantCapabilityUsageError } from '@muyun/web-core';
import type { MenuRecord, MenuTreeNode } from '@muyun/web-contracts';
import type { AssistantCapability, AssistantInvocationToken } from '@muyun/web-core';
import { getMenuNavigationTarget } from './menuNavigation';

export function createWorkbenchAssistantCapabilities(
  menus: () => MenuTreeNode[],
  openMenu: (menu: MenuRecord) => boolean,
  settleNavigation: (signal?: AbortSignal) => Promise<void | AssistantInvocationToken> = async () => {},
): AssistantCapability[] {
  return [findMenuCapability(menus), openMenuCapability(menus, openMenu, settleNavigation)];
}

function findMenuCapability(menus: () => MenuTreeNode[]): AssistantCapability<{ query: string }> {
  return {
    effect: 'read',
    descriptor: {
      code: 'workbench.find-menu',
      description: 'Find candidate business entries from the current user visible menu tree',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['query'],
        properties: { query: { type: 'string', minLength: 1 } },
      },
    },
    parseInput(input) {
      const query = recordString(input, 'query');
      if (!query) throw new AssistantCapabilityUsageError('workbench.find-menu requires a non-empty query');
      return { query };
    },
    async execute({ query }) {
      const normalized = query.toLocaleLowerCase();
      return flattenMenus(menus())
        .filter(({ menu }) => getMenuNavigationTarget(menu) !== undefined)
        .filter(({ menu, path }) =>
          [menu.title, menu.moduleAlias, path.join(' ')].some((value) =>
            value?.toLocaleLowerCase().includes(normalized),
          ),
        )
        .slice(0, 10)
        .map(({ menu, path }) => ({
          menuId: menu.id,
          title: menu.title,
          moduleAlias: menu.moduleAlias,
          path,
        }));
    },
  };
}

function openMenuCapability(
  menus: () => MenuTreeNode[],
  openMenu: (menu: MenuRecord) => boolean,
  settleNavigation: (signal?: AbortSignal) => Promise<void | AssistantInvocationToken>,
): AssistantCapability<{ menuId: string }> {
  return {
    effect: 'page',
    descriptor: {
      code: 'workbench.open-menu',
      description: 'Open one exact entry from the current user visible menu tree',
      inputSchema: {
        type: 'object',
        additionalProperties: false,
        required: ['menuId'],
        properties: { menuId: { type: 'string', minLength: 1 } },
      },
    },
    parseInput(input) {
      const menuId = recordString(input, 'menuId');
      if (!menuId) throw new AssistantCapabilityUsageError('workbench.open-menu requires a non-empty menuId');
      return { menuId };
    },
    async execute({ menuId }, context) {
      const { signal } = context;
      if (signal.aborted) throw new DOMException('Assistant invocation was cancelled', 'AbortError');
      const menu = flattenMenus(menus()).find(
        (candidate) => candidate.menu.id === menuId && getMenuNavigationTarget(candidate.menu) !== undefined,
      )?.menu;
      if (!menu) throw new AssistantCapabilityUsageError(`Visible menu is unavailable: ${menuId}`);
      const opened = context.applyEffect(
        () => openMenu(menu),
        () => settleNavigation(context.cancellationSignal ?? signal),
      );
      if (!opened) throw new AssistantCapabilityUsageError(`Visible menu cannot be opened: ${menuId}`);
      return { openedMenuId: menu.id, title: menu.title };
    },
  };
}

interface FlatMenu {
  menu: MenuRecord;
  path: string[];
}

function flattenMenus(nodes: readonly MenuTreeNode[], path: string[] = []): FlatMenu[] {
  return nodes.flatMap((node) => {
    const nextPath = [...path, node.record.title];
    return [{ menu: node.record, path: nextPath }, ...flattenMenus(node.children ?? [], nextPath)];
  });
}

function recordString(input: unknown, key: string) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return undefined;
  const value = (input as Record<string, unknown>)[key];
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}
