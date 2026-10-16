import type { Option, OptionItemDescriptor } from '@muyun/web-contracts';
import { presentationLabel, platformLanguageContext, type LanguageContext } from '@muyun/web-core';

export interface UiTreeSelectNode {
  value: string;
  title: string;
  disabled?: boolean;
  children?: UiTreeSelectNode[];
}

/** Disposable labels for UI search/selection; the source items and persisted codes stay intact. */
export function optionItemsForDisplay(
  items: readonly OptionItemDescriptor[],
  language: LanguageContext,
): OptionItemDescriptor[] {
  return items.map((item) => ({ ...item, title: presentationLabel(item.title, item.titleText, language) }));
}

export function optionItemsToOptions(
  items: OptionItemDescriptor[],
  language: LanguageContext = platformLanguageContext,
): Option[] {
  return optionItemsForDisplay(items, language).map((item) => ({
    label: item.title,
    value: item.code,
    disabled: !item.enabled,
  }));
}

export function hasOptionHierarchy(items: OptionItemDescriptor[]): boolean {
  return items.some((item) => Boolean(item.parentCode));
}

export function optionItemsToTree(
  items: OptionItemDescriptor[],
  language: LanguageContext = platformLanguageContext,
): UiTreeSelectNode[] {
  const nodes = new Map<string, UiTreeSelectNode>();
  const roots: UiTreeSelectNode[] = [];
  optionItemsForDisplay(items, language).forEach((item) =>
    nodes.set(item.code, {
      value: item.code,
      title: item.title,
      disabled: !item.enabled,
      children: [],
    }),
  );
  items.forEach((item) => {
    const node = nodes.get(item.code)!;
    const parent = item.parentCode ? nodes.get(item.parentCode) : undefined;
    if (parent) {
      parent.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  return roots;
}

/** Option identities remain stable for scalar, array and JSON-array storage forms. */
export function selectionCodesOf(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String).filter(Boolean);
  if (typeof value !== 'string') return value == null ? [] : [String(value)];
  const trimmed = value.trim();
  if (!trimmed) return [];
  if (trimmed.startsWith('[')) {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      if (Array.isArray(parsed)) return parsed.map(String).filter(Boolean);
    } catch {
      /* A scalar code may begin with "[". */
    }
  }
  return [value];
}
