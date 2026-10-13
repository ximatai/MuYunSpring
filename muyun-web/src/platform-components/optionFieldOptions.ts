import type { Option, OptionItemDescriptor } from '@muyun/web-contracts';
import { presentationLabel, platformLanguageContext, type LanguageContext } from '@muyun/web-core';

export interface UiTreeSelectNode {
  value: string;
  title: string;
  disabled?: boolean;
  children?: UiTreeSelectNode[];
}

export function optionItemsToOptions(
  items: OptionItemDescriptor[],
  language: LanguageContext = platformLanguageContext,
): Option[] {
  return items.map((item) => ({
    label: presentationLabel(item.title, item.titleText, language),
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
  items.forEach((item) =>
    nodes.set(item.code, {
      value: item.code,
      title: presentationLabel(item.title, item.titleText, language),
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
