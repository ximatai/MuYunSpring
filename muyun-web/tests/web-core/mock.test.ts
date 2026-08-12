import { describe, expect, it } from 'vitest';
import type { MenuRecord, WebTreeNode } from '@muyun/web-contracts';
import { getMenuNavigationTarget } from '@/platform-workbench/menuNavigation.ts';
import { mockMenuTree } from '@/web-core/mock.ts';

describe('mockMenuTree', () => {
  it('covers one through five menu levels with four or five children per branch', () => {
    const levelCounts = new Map<number, number>();

    visitMenuLevel(mockMenuTree, 1, levelCounts);

    expect(mockMenuTree).toHaveLength(5);
    expect([...levelCounts.keys()]).toEqual([1, 2, 3, 4, 5]);
    expect(mockMenuTree.map(maxMenuDepth)).toEqual([5, 4, 4, 4, 4]);
  });

  it('mixes module-backed and structural branches at every expandable level', () => {
    const branchCapabilities = new Map<number, Set<boolean>>();

    visitMenuCapabilities(mockMenuTree, 1, branchCapabilities);

    expect([...branchCapabilities.keys()]).toEqual([1, 2, 3, 4]);
    for (const capabilities of branchCapabilities.values()) {
      expect(capabilities).toEqual(new Set([true, false]));
    }
  });
});

function visitMenuLevel(nodes: WebTreeNode<MenuRecord>[], level: number, levelCounts: Map<number, number>) {
  levelCounts.set(level, (levelCounts.get(level) ?? 0) + nodes.length);

  for (const node of nodes) {
    if (node.children.length === 0) {
      continue;
    }

    expect(node.children.length).toBeGreaterThanOrEqual(4);
    expect(node.children.length).toBeLessThanOrEqual(5);
    for (const child of node.children) {
      expect(child.record.parentId).toBe(node.record.id);
    }
    visitMenuLevel(node.children, level + 1, levelCounts);
  }
}

function maxMenuDepth(node: WebTreeNode<MenuRecord>): number {
  return node.children.length === 0 ? 1 : 1 + Math.max(...node.children.map(maxMenuDepth));
}

function visitMenuCapabilities(
  nodes: WebTreeNode<MenuRecord>[],
  level: number,
  branchCapabilities: Map<number, Set<boolean>>,
) {
  for (const node of nodes) {
    const carriesModule = Boolean(getMenuNavigationTarget(node.record));
    if (node.children.length === 0) {
      expect(carriesModule).toBe(true);
      continue;
    }

    const capabilities = branchCapabilities.get(level) ?? new Set<boolean>();
    capabilities.add(carriesModule);
    branchCapabilities.set(level, capabilities);
    visitMenuCapabilities(node.children, level + 1, branchCapabilities);
  }
}
