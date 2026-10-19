import type { MenuNavigationTarget, MenuRecord, MenuTreeNode } from '@muyun/web-contracts';
import { getMenuNavigationTarget } from './menuNavigation';
import { presentationLabel, type LanguageContext } from '@muyun/web-core';

export interface WorkbenchMenuNode {
  record: MenuRecord;
  children: WorkbenchMenuNode[];
  target?: MenuNavigationTarget;
  navigable: boolean;
  hasChildren: boolean;
}

export interface WorkbenchMegaMenuModel {
  root: WorkbenchMenuNode;
  groups: WorkbenchMenuNode[];
  activeDeepRoot?: WorkbenchMenuNode;
}

export function createWorkbenchMenuNodes(
  nodes: MenuTreeNode[],
  language?: LanguageContext,
): WorkbenchMenuNode[] {
  return nodes.map((node) => createWorkbenchMenuNode(node, language));
}

export function filterWorkbenchMenuNodes(nodes: WorkbenchMenuNode[], keyword: string): WorkbenchMenuNode[] {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) {
    return nodes;
  }

  return nodes
    .map((node) => {
      const children = filterWorkbenchMenuNodes(node.children, normalized);
      if (!menuNodeMatches(node, normalized)) {
        return children.length > 0 ? { ...node, children } : undefined;
      }

      if (node.navigable) {
        return { ...node, children };
      }

      const navigableChildren = retainNavigableMenuNodes(node.children);
      return navigableChildren.length > 0 ? { ...node, children: navigableChildren } : undefined;
    })
    .filter((node): node is WorkbenchMenuNode => Boolean(node));
}

function retainNavigableMenuNodes(nodes: WorkbenchMenuNode[]): WorkbenchMenuNode[] {
  return nodes
    .map((node) => {
      const children = retainNavigableMenuNodes(node.children);
      return node.navigable || children.length > 0 ? { ...node, children } : undefined;
    })
    .filter((node): node is WorkbenchMenuNode => Boolean(node));
}

export function findWorkbenchMenuPath(
  nodes: WorkbenchMenuNode[],
  menuId: string,
  path: WorkbenchMenuNode[] = [],
): WorkbenchMenuNode[] {
  for (const node of nodes) {
    const nextPath = [...path, node];
    if (node.record.id === menuId) {
      return nextPath;
    }

    const childPath = findWorkbenchMenuPath(node.children, menuId, nextPath);
    if (childPath.length > 0) {
      return childPath;
    }
  }

  return [];
}

export function findWorkbenchMenuNodeById(
  nodes: WorkbenchMenuNode[],
  menuId: string,
): WorkbenchMenuNode | undefined {
  for (const node of nodes) {
    if (node.record.id === menuId) {
      return node;
    }

    const child = findWorkbenchMenuNodeById(node.children, menuId);
    if (child) {
      return child;
    }
  }

  return undefined;
}

export function firstDeepRootIdOf(node: WorkbenchMenuNode): string | undefined {
  for (const group of node.children) {
    for (const child of group.children) {
      if (child.hasChildren) {
        return child.record.id;
      }
    }
  }

  return undefined;
}

export function buildWorkbenchMegaMenuModel(
  root: WorkbenchMenuNode,
  activeDeepRootId: string | undefined,
): WorkbenchMegaMenuModel {
  const node = activeDeepRootId ? findWorkbenchMenuNodeById(root.children, activeDeepRootId) : undefined;
  const groups = root.children;
  return {
    root,
    groups,
    activeDeepRoot: node?.hasChildren ? node : undefined,
  };
}

function createWorkbenchMenuNode(node: MenuTreeNode, language?: LanguageContext): WorkbenchMenuNode {
  const children = createWorkbenchMenuNodes(node.children, language);
  const target = getMenuNavigationTarget(node.record);
  return {
    record: language
      ? { ...node.record, title: presentationLabel(node.record.title, node.record.titleText, language) }
      : node.record,
    children,
    target,
    navigable: Boolean(target),
    hasChildren: children.length > 0,
  };
}

function menuNodeMatches(node: WorkbenchMenuNode, keyword: string): boolean {
  return (
    node.record.title.toLowerCase().includes(keyword) ||
    node.record.moduleAlias?.toLowerCase().includes(keyword) === true ||
    node.record.route?.toLowerCase().includes(keyword) === true
  );
}
