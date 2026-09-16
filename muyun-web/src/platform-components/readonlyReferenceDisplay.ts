import type { ResolvedReferenceFieldDescriptor } from '@muyun/web-contracts';

type ReferenceSummary = Record<string, unknown>;

export interface ReadonlyReferenceDisplayItem {
  id: string;
  label: string;
  /** Only a delivered, usable projection may enter the target-detail browser. */
  browseable: boolean;
}

/**
 * Formats only record-projected reference facts. It never resolves candidates: an absent title
 * remains the persisted ID, which distinguishes an unavailable projection from a deleted or
 * unauthorized target.
 */
export function readonlyReferenceDisplay(
  reference: Pick<ResolvedReferenceFieldDescriptor, 'cardinality'>,
  value: unknown,
  summary: unknown,
): string | undefined {
  return readonlyReferenceDisplayItems(reference, value, summary)
    .map((item) => item.label)
    .join('、');
}

/**
 * Keeps the persisted reference order while retaining enough projection identity for the shared
 * read-only browser. A raw ID intentionally remains non-interactive: it may be absent, deleted,
 * or hidden by a target data scope.
 */
export function readonlyReferenceDisplayItems(
  reference: Pick<ResolvedReferenceFieldDescriptor, 'cardinality'>,
  value: unknown,
  summary: unknown,
): ReadonlyReferenceDisplayItem[] {
  const ids = referenceIds(value);
  if (reference.cardinality === 'ONE') return ids.length ? [singleItem(ids[0]!, summary)] : [];
  return multipleItems(ids, summary);
}

function singleItem(id: string, summary: unknown): ReadonlyReferenceDisplayItem {
  if (isText(summary)) return { id, label: summary, browseable: true };
  if (!isSummary(summary) || String(summary.id ?? '') !== id) return rawItem(id);
  return summaryItem(id, summary);
}

function multipleItems(ids: readonly string[], summary: unknown): ReadonlyReferenceDisplayItem[] {
  if (!Array.isArray(summary)) return ids.map(rawItem);
  if (summary.length === ids.length && summary.every(isText)) {
    return ids.map((id, index) => ({ id, label: summary[index] as string, browseable: true }));
  }
  const summariesById = new Map<string, ReferenceSummary>();
  for (const item of summary) {
    if (isSummary(item) && isText(item.id)) summariesById.set(String(item.id), item);
  }
  return ids.map((id) => {
    const item = summariesById.get(id);
    return item ? summaryItem(id, item) : rawItem(id);
  });
}

function referenceIds(value: unknown): string[] {
  if (Array.isArray(value)) return value.flatMap(referenceId);
  if (typeof value === 'string') {
    const text = value.trim();
    if (!text) return [];
    if (text.startsWith('[')) {
      try {
        const parsed: unknown = JSON.parse(text);
        if (Array.isArray(parsed)) return parsed.flatMap(referenceId);
      } catch {
        // Keep a scalar ID that happens to start with "[" visible.
      }
    }
    return [value];
  }
  return referenceId(value);
}

function referenceId(value: unknown): string[] {
  if (typeof value === 'string' || typeof value === 'number') return String(value) ? [String(value)] : [];
  return [];
}

function summaryDisplay(summary: ReferenceSummary, fallbackId: string) {
  const title = isText(summary.title) ? summary.title : undefined;
  const alias = isText(summary.alias) ? summary.alias : undefined;
  const label = title && alias && title !== alias ? `${title} (${alias})` : (title ?? alias ?? fallbackId);
  return summary.unavailable === true ? `${label}（不可用）` : label;
}

function summaryItem(id: string, summary: ReferenceSummary): ReadonlyReferenceDisplayItem {
  const label = summaryDisplay(summary, id);
  return {
    id,
    label,
    browseable: summary.unavailable !== true && (isText(summary.title) || isText(summary.alias)),
  };
}

function rawItem(id: string): ReadonlyReferenceDisplayItem {
  return { id, label: id, browseable: false };
}

function isSummary(value: unknown): value is ReferenceSummary {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}
