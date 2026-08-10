import type { MenuNavigationTarget, MenuRecord, MenuTreeNode } from '@muyun/web-contracts';
import { getMenuNavigationTarget } from './menuNavigation';

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
  columns: WorkbenchMenuNode[][];
  activeDeepRoot?: WorkbenchMenuNode;
}

export function createWorkbenchMenuNodes(nodes: MenuTreeNode[]): WorkbenchMenuNode[] {
  return nodes.map(createWorkbenchMenuNode);
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
  columnCount = 3,
): WorkbenchMegaMenuModel {
  const node = activeDeepRootId ? findWorkbenchMenuNodeById(root.children, activeDeepRootId) : undefined;
  const groups = root.children;
  return {
    root,
    groups,
    columns: buildMegaMenuColumns(groups, columnCount),
    activeDeepRoot: node?.hasChildren ? node : undefined,
  };
}

function buildMegaMenuColumns(groups: WorkbenchMenuNode[], columnCount: number): WorkbenchMenuNode[][] {
  if (groups.length === 0) {
    return [];
  }

  const normalizedColumnCount = Math.max(1, Math.min(Math.floor(columnCount), groups.length));
  const columns: WorkbenchMenuNode[][] = Array.from({ length: normalizedColumnCount }, () => []);
  const targetWeight =
    groups.reduce((total, group) => total + menuGroupWeight(group), 0) / normalizedColumnCount;
  let columnIndex = 0;
  let currentWeight = 0;

  for (let index = 0; index < groups.length; index += 1) {
    const group = groups[index];
    const groupWeight = menuGroupWeight(group);
    const remainingGroups = groups.length - index;
    const remainingColumns = normalizedColumnCount - columnIndex;
    const currentColumn = columns[columnIndex];
    const shouldStartNextColumn =
      currentColumn.length > 0 &&
      columnIndex < normalizedColumnCount - 1 &&
      (remainingGroups <= remainingColumns || currentWeight + groupWeight > targetWeight);

    if (shouldStartNextColumn) {
      columnIndex += 1;
      currentWeight = 0;
    }

    columns[columnIndex].push(group);
    currentWeight += groupWeight;
  }

  return columns;
}

function menuGroupWeight(group: WorkbenchMenuNode): number {
  const titleWeight = titleLengthWeight(group.record.title);
  const childWeight = group.children.reduce((total, child) => {
    const branchWeight = child.hasChildren ? 0.7 + child.children.length * 0.25 : 0;
    return total + 1 + branchWeight + titleLengthWeight(child.record.title) * 0.4;
  }, 0);

  return 1.25 + titleWeight + childWeight;
}

function titleLengthWeight(title: string): number {
  return Math.max(0, Math.ceil(title.length / 10) - 1) * 0.35;
}

function createWorkbenchMenuNode(node: MenuTreeNode): WorkbenchMenuNode {
  const children = createWorkbenchMenuNodes(node.children);
  const target = getMenuNavigationTarget(node.record);
  return {
    record: node.record,
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
