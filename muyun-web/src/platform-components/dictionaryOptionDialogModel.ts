import type { Option, OptionItemDescriptor } from '@muyun/web-contracts';

export type DictionarySelectionMode = 'SINGLE' | 'MULTIPLE';
export type DictionaryOptionValue = string | string[] | undefined;

export interface DictionaryOptionTreeNode {
  value: string;
  title: string;
  disabled?: boolean;
  children?: DictionaryOptionTreeNode[];
}

interface ItemNode {
  item: OptionItemDescriptor;
  children: ItemNode[];
}

/**
 * Normalizes the persisted dictionary wire value. Dictionary values are always codes;
 * titles are display facts and must never leak into the form draft.
 */
export function dictionaryOptionCodes(value: unknown, selectionMode: DictionarySelectionMode): string[] {
  const candidates = Array.isArray(value) ? value : value == null ? [] : [value];
  const codes = candidates.filter((item): item is string => typeof item === 'string');
  const unique = [...new Set(codes)];
  return selectionMode === 'SINGLE' ? unique.slice(0, 1) : unique;
}

export function dictionaryOptionValue(
  codes: readonly string[],
  selectionMode: DictionarySelectionMode,
): DictionaryOptionValue {
  return selectionMode === 'SINGLE' ? codes[0] : [...codes];
}

export function dictionaryOptionItemsToOptions(
  items: readonly OptionItemDescriptor[],
  keyword = '',
): Option[] {
  return items
    .filter((item) => matches(item, keyword))
    .map((item) => ({ label: item.title, value: item.code, disabled: !item.enabled }));
}

/**
 * Retains the parent/child relationship carried by `parentCode`. Missing parents are rendered
 * as an explicit disabled structural node instead of silently promoting their children to roots.
 */
export function dictionaryOptionItemsToTree(
  items: readonly OptionItemDescriptor[],
  keyword = '',
): DictionaryOptionTreeNode[] {
  const byCode = new Map<string, ItemNode>();
  const roots: ItemNode[] = [];
  const missingParents = new Map<string, ItemNode[]>();

  for (const item of items) {
    // A duplicate code is invalid server data. Keeping the first item preserves deterministic
    // selection identity without manufacturing a second selectable value.
    if (!byCode.has(item.code)) byCode.set(item.code, { item, children: [] });
  }
  for (const node of byCode.values()) {
    const parentCode = node.item.parentCode;
    const parent = parentCode ? byCode.get(parentCode) : undefined;
    if (!parentCode) {
      roots.push(node);
    } else if (parent && parent !== node) {
      parent.children.push(node);
    } else if (parentCode) {
      const children = missingParents.get(parentCode) ?? [];
      children.push(node);
      missingParents.set(parentCode, children);
    }
  }

  const forest: DictionaryOptionTreeNode[] = [];
  const visited = new Set<string>();
  for (const root of roots) {
    const node = toTreeNode(root, visited);
    if (node) forest.push(node);
  }
  for (const [parentCode, children] of missingParents) {
    const childNodes = children
      .map((child) => toTreeNode(child, visited))
      .filter((node): node is DictionaryOptionTreeNode => node != null);
    if (childNodes.length) {
      forest.push({
        value: `__missing_dictionary_parent__:${parentCode}`,
        title: `缺少父级：${parentCode}`,
        disabled: true,
        children: childNodes,
      });
    }
  }
  // Cycles have no root. Preserve them visibly instead of flattening or dropping the data.
  for (const node of byCode.values()) {
    if (visited.has(node.item.code)) continue;
    const cycleNode = toTreeNode(node, visited);
    if (cycleNode) {
      forest.push({
        value: `__invalid_dictionary_hierarchy__:${node.item.code}`,
        title: '无效的字典层级',
        disabled: true,
        children: [cycleNode],
      });
    }
  }
  return filterTree(forest, keyword);
}

/** Existing disabled values remain visible, but only enabled option codes can be newly added. */
export function selectableDictionaryCodes(
  requested: unknown,
  externalCodes: readonly string[],
  items: readonly OptionItemDescriptor[],
  selectionMode: DictionarySelectionMode,
): string[] {
  const external = new Set(externalCodes);
  const enabled = new Set(items.filter((item) => item.enabled).map((item) => item.code));
  return dictionaryOptionCodes(requested, selectionMode)
    .filter((code) => external.has(code) || enabled.has(code))
    .slice(0, selectionMode === 'SINGLE' ? 1 : undefined);
}

function toTreeNode(node: ItemNode, visited: Set<string>): DictionaryOptionTreeNode | undefined {
  if (visited.has(node.item.code)) return undefined;
  visited.add(node.item.code);
  const children = node.children
    .map((child) => toTreeNode(child, visited))
    .filter((child): child is DictionaryOptionTreeNode => child != null);
  return {
    value: node.item.code,
    title: node.item.title,
    disabled: !node.item.enabled,
    ...(children.length ? { children } : {}),
  };
}

function filterTree(nodes: readonly DictionaryOptionTreeNode[], keyword: string): DictionaryOptionTreeNode[] {
  const normalizedKeyword = keyword.trim().toLocaleLowerCase();
  if (!normalizedKeyword) return nodes.map(copyTreeNode);
  return nodes.flatMap((node) => {
    const children = filterTree(node.children ?? [], normalizedKeyword);
    if (
      node.title.toLocaleLowerCase().includes(normalizedKeyword) ||
      node.value.toLocaleLowerCase().includes(normalizedKeyword)
    ) {
      return [copyTreeNode(node)];
    }
    return children.length ? [{ ...node, children }] : [];
  });
}

function copyTreeNode(node: DictionaryOptionTreeNode): DictionaryOptionTreeNode {
  return { ...node, ...(node.children ? { children: node.children.map(copyTreeNode) } : {}) };
}

function matches(item: OptionItemDescriptor, keyword: string) {
  const normalizedKeyword = keyword.trim().toLocaleLowerCase();
  return (
    !normalizedKeyword ||
    item.title.toLocaleLowerCase().includes(normalizedKeyword) ||
    item.code.toLocaleLowerCase().includes(normalizedKeyword)
  );
}
