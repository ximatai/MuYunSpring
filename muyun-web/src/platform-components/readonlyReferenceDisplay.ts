import type { ResolvedReferenceFieldDescriptor } from '@muyun/web-contracts';

type ReferenceSummary = Record<string, unknown>;

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
  const ids = referenceIds(value);
  // A cleared persisted value is authoritative over a stale read projection.
  if (ids.length === 0) return '';
  if (reference.cardinality === 'ONE') return singleDisplay(ids[0]!, summary);
  return multipleDisplay(ids, summary);
}

function singleDisplay(id: string, summary: unknown) {
  if (isText(summary)) return summary;
  if (!isSummary(summary) || String(summary.id ?? '') !== id) return id;
  return summaryDisplay(summary, id);
}

function multipleDisplay(ids: readonly string[], summary: unknown) {
  if (!Array.isArray(summary)) return ids.join('、');
  // Legacy title arrays did not carry IDs. They remain valid only when one title exists for
  // every persisted ID, in exactly the stored order.
  if (summary.length === ids.length && summary.every(isText)) return summary.join('、');

  const summariesById = new Map<string, ReferenceSummary>();
  for (const item of summary) {
    if (isSummary(item) && isText(item.id)) summariesById.set(String(item.id), item);
  }
  return ids
    .map((id) => {
      const item = summariesById.get(id);
      return item ? summaryDisplay(item, id) : id;
    })
    .join('、');
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

function isSummary(value: unknown): value is ReferenceSummary {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}
