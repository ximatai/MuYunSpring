import type { UiTreeNode } from './types';

export interface TreeEntry {
  node: UiTreeNode;
  parent?: string;
  index: number;
}

export function indexTree(nodes: readonly UiTreeNode[], flat = false) {
  const entries = new Map<string, TreeEntry>();
  function visit(items: readonly UiTreeNode[], parent?: string) {
    items.forEach((node, index) => {
      if (!node.key?.trim() || entries.has(node.key)) throw new Error(`树节点身份重复或为空：${node.key}`);
      entries.set(node.key, { node, parent, index });
      if (!flat && node.children) visit(node.children, node.key);
    });
  }
  visit(nodes);
  return entries;
}

export function mergeTreePage(existing: UiTreeNode[], incoming: UiTreeNode[]) {
  indexTree(incoming);
  const updates = new Map(incoming.map((node) => [node.key, node]));
  return [
    ...existing.map((node) => {
      const replacement = updates.get(node.key);
      updates.delete(node.key);
      return replacement ?? node;
    }),
    ...updates.values(),
  ];
}

export type TreeChange = { key: string; kind: 'enter' | 'leave' | 'move' | 'reparent' | 'update' };
export function treeSnapshot(nodes: UiTreeNode[], flat = false) {
  return new Map(
    [...indexTree(nodes, flat)].map(([key, entry]) => [
      key,
      {
        parent: entry.parent,
        index: entry.index,
        content: JSON.stringify([
          entry.node.title,
          entry.node.secondary,
          entry.node.tag,
          entry.node.muted,
          entry.node.disabled,
        ]),
      },
    ]),
  );
}

export function treeChanges(before: ReturnType<typeof treeSnapshot>, after: ReturnType<typeof treeSnapshot>) {
  const changes: TreeChange[] = [];
  before.forEach((_, key) => {
    if (!after.has(key)) changes.push({ key, kind: 'leave' });
  });
  after.forEach((entry, key) => {
    const old = before.get(key);
    if (!old) changes.push({ key, kind: 'enter' });
    else if (old.parent !== entry.parent) changes.push({ key, kind: 'reparent' });
    else if (old.index !== entry.index) changes.push({ key, kind: 'move' });
    else if (old.content !== entry.content) changes.push({ key, kind: 'update' });
  });
  return changes;
}
