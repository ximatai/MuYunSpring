import { recordPresentationLabel, platformLanguageContext, type LanguageContext } from '@muyun/web-core';
import type { WebTreeNode } from '@muyun/web-contracts';

export interface TreeRecordBase {
  id?: string;
  title?: string;
  code?: string;
  enabled?: boolean;
}

export function defaultTreeRecordTitle<TRecord extends TreeRecordBase>(
  record: TRecord,
  fallback?: string,
  language: LanguageContext = platformLanguageContext,
) {
  return (
    recordPresentationLabel(record, 'title', language) ??
    record.code ??
    record.id ??
    fallback ??
    language.t('platform.record.unnamed', '未命名记录')
  );
}

export function flattenTreeRecords<TRecord>(nodes: WebTreeNode<TRecord>[]): TRecord[] {
  return nodes.flatMap((node) => [node.record, ...flattenTreeRecords(node.children)]);
}

export function filterTreeRecords<TRecord>(
  nodes: WebTreeNode<TRecord>[],
  keyword: string,
  matches: (record: TRecord, normalizedKeyword: string) => boolean,
): WebTreeNode<TRecord>[] {
  const normalized = keyword.trim().toLowerCase();
  if (!normalized) {
    return nodes;
  }
  return nodes.flatMap((node) => {
    const children = filterTreeRecords(node.children, normalized, matches);
    if (matches(node.record, normalized) || children.length > 0) {
      return [{ ...node, children }];
    }
    return [];
  });
}

export function defaultTreeRecordMatches<TRecord extends TreeRecordBase>(
  record: TRecord,
  normalizedKeyword: string,
  titleOf: (record: TRecord) => string = defaultTreeRecordTitle,
) {
  return [titleOf(record), record.code, record.id]
    .filter(Boolean)
    .some((item) => String(item).toLowerCase().includes(normalizedKeyword));
}

export function firstTwoTreeLevels<TRecord extends { id?: string }>(nodes: WebTreeNode<TRecord>[]) {
  return nodes.flatMap((node) => [
    ...(node.record.id ? [node.record.id] : []),
    ...node.children.flatMap((child) => (child.record.id ? [child.record.id] : [])),
  ]);
}

export function expandAllTreeRecords<TRecord extends { id?: string }>(node: WebTreeNode<TRecord>): string[] {
  return [...(node.record.id ? [node.record.id] : []), ...node.children.flatMap(expandAllTreeRecords)];
}
