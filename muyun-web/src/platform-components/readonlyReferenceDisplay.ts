import { recordPresentationLabel, platformLanguageContext, type LanguageContext } from '@muyun/web-core';
import type { ResolvedReferenceFieldDescriptor } from '@muyun/web-contracts';

type ReferenceSummary = Record<string, unknown>;
type ReferenceDisplayFact = ReadonlyReferenceDisplayItem & { hasName: boolean };

export interface ReadonlyReferenceDisplayItem {
  id: string;
  label: string;
  /** Only a delivered, usable projection may enter the target-detail browser. */
  browseable: boolean;
}

/** Reads a scalar companion's display resource without changing its owner record or summary identity. */
export function readonlyReferenceSummaryOf(
  record: Record<string, unknown>,
  field: string | undefined,
  language: LanguageContext = platformLanguageContext,
): unknown {
  if (!field) return undefined;
  const summary = record[field];
  return typeof summary === 'string' ? recordPresentationLabel(record, field, language) : summary;
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
  language: LanguageContext = platformLanguageContext,
): string | undefined {
  return readonlyReferenceDisplayItems(reference, value, summary, language)
    .map((item) => item.label)
    .join(language.t('platform.list.separator', '、'));
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
  language: LanguageContext = platformLanguageContext,
): ReadonlyReferenceDisplayItem[] {
  return referenceDisplayFacts(reference, value, summary, language).map(({ id, label, browseable }) => ({
    id,
    label,
    browseable,
  }));
}

/** Name-only read projection for assistants; raw identifiers never stand in for missing names. */
export function readonlyReferenceNameProjection(
  reference: Pick<ResolvedReferenceFieldDescriptor, 'cardinality'>,
  value: unknown,
  summary: unknown,
  language: LanguageContext = platformLanguageContext,
): { selected: boolean; display?: string } {
  const facts = referenceDisplayFacts(reference, value, summary, language);
  if (!facts.some((fact) => fact.hasName)) return { selected: facts.length > 0 };
  return {
    selected: true,
    display: facts
      .map((fact) =>
        fact.hasName
          ? fact.label
          : language.t('platform.reference.selected-name-unavailable', '已选择（名称暂不可用）'),
      )
      .join(language.t('platform.list.separator', '、')),
  };
}

function referenceDisplayFacts(
  reference: Pick<ResolvedReferenceFieldDescriptor, 'cardinality'>,
  value: unknown,
  summary: unknown,
  language: LanguageContext,
): ReferenceDisplayFact[] {
  const ids = referenceIds(value);
  if (reference.cardinality === 'ONE') return ids.length ? [singleItem(ids[0]!, summary, language)] : [];
  return multipleItems(ids, summary, language);
}

function singleItem(id: string, summary: unknown, language: LanguageContext): ReferenceDisplayFact {
  if (isText(summary)) return { id, label: summary, browseable: true, hasName: true };
  if (!isSummary(summary) || String(summary.id ?? '') !== id) return rawItem(id);
  return summaryItem(id, summary, language);
}

function multipleItems(
  ids: readonly string[],
  summary: unknown,
  language: LanguageContext,
): ReferenceDisplayFact[] {
  if (!Array.isArray(summary)) return ids.map(rawItem);
  if (summary.length === ids.length && summary.every(isText)) {
    return ids.map((id, index) => ({ id, label: summary[index] as string, browseable: true, hasName: true }));
  }
  const summariesById = new Map<string, ReferenceSummary>();
  for (const item of summary) {
    if (isSummary(item) && isText(item.id)) summariesById.set(String(item.id), item);
  }
  return ids.map((id) => {
    const item = summariesById.get(id);
    return item ? summaryItem(id, item, language) : rawItem(id);
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

function summaryDisplay(summary: ReferenceSummary, fallbackId: string, language: LanguageContext) {
  const title = isText(summary.title) ? recordPresentationLabel(summary, 'title', language) : undefined;
  const alias = isText(summary.alias) ? summary.alias : undefined;
  const label = title && alias && title !== alias ? `${title} (${alias})` : (title ?? alias ?? fallbackId);
  return summary.unavailable === true
    ? language.t('platform.reference.unavailable', '{label}（不可用）', { label })
    : label;
}

function summaryItem(id: string, summary: ReferenceSummary, language: LanguageContext): ReferenceDisplayFact {
  const label = summaryDisplay(summary, id, language);
  return {
    id,
    label,
    browseable: summary.unavailable !== true && (isText(summary.title) || isText(summary.alias)),
    hasName: isText(summary.title) || isText(summary.alias),
  };
}

function rawItem(id: string): ReferenceDisplayFact {
  return { id, label: id, browseable: false, hasName: false };
}

function isSummary(value: unknown): value is ReferenceSummary {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isText(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}
