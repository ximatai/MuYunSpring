import { toRaw } from 'vue';
import { businessContentLanguageContext } from './businessContentLanguage';
import type { BusinessContentTextPresentation } from '@muyun/web-contracts';
import type { TextReference, TextPresentation, TextPresentationDocument } from '@muyun/web-contracts';
import type { LanguageContext, TextResolution } from './language';
import { sha256Hex } from './sha256';

/** Snapshot a producer's concrete CODE original; arguments format deployed resources, never business values in the fallback. */
export function createCodeTextPresentation(
  key: string,
  originalText: string,
  context: LanguageContext,
  args?: Record<string, unknown>,
): TextPresentation {
  if (!key.trim()) throw new Error('CODE text key must not be blank');
  return {
    source: {
      sourceKind: 'CODE',
      sourceId: key,
      sourceVersion: `sha256-${sha256Hex(originalText)}`,
      originalText,
    },
    languages: Object.fromEntries(
      context.catalog.value.languages.map(({ tag }) => {
        const resolution = context.resolveForLanguage(tag, key, undefined, args);
        return [tag, resolution.missing ? { ...resolution, text: originalText } : resolution];
      }),
    ),
  };
}

export function resolvePresentationText(text: TextPresentation, context: LanguageContext): TextResolution {
  const requestedTag = context.languageTag.value;
  for (const tag of context.fallbackLanguageTags.value) {
    const resolution = text.languages[tag];
    if (resolution)
      return {
        ...resolution,
        resourceTag: resolution.resourceTag ?? undefined,
        requestedTag,
        fallback: resolution.fallback || tag !== requestedTag,
      };
  }
  return { text: text.source.originalText, requestedTag, missing: true, fallback: true };
}

/** Typed labels outside module descriptors use the same immutable presentation resource. */
export function presentationLabel(
  original: string,
  text: TextPresentation | undefined,
  context: LanguageContext,
): string {
  return text ? resolvePresentationText(text, context).text : original;
}

/** Restore source facts for execution/dirty checks; projections remain separate and disposable. */
export function originalPresentation<T extends TextPresentationDocument>(document: T): T {
  return project(document, (text) => text.source.originalText);
}

export function translatedPresentation<T extends TextPresentationDocument>(
  document: T,
  context: LanguageContext,
): T {
  return project(document, (text) => resolvePresentationText(text, context).text);
}

function project<T extends TextPresentationDocument>(
  document: T,
  textOf: (text: TextPresentation) => string,
): T {
  if (!document.textBindings || !Object.keys(document.textBindings).length) return document;
  const output = structuredClone(toRaw(document));
  for (const [path, text] of Object.entries(document.textBindings)) {
    const segments = path
      .split('/')
      .slice(1)
      .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'));
    // A resource payload cannot assign a prototype, alter structure or manufacture new properties.
    if (
      (path !== '/title' &&
        segments[0] !== 'uiDescriptor' &&
        !(
          segments.length === 3 &&
          segments[0] === 'actions' &&
          /^\d+$/.test(segments[1]!) &&
          segments[2] === 'title'
        )) ||
      !path.startsWith('/') ||
      !segments.length ||
      segments.some((segment) => ['__proto__', 'constructor', 'prototype'].includes(segment))
    )
      continue;
    let parent: unknown = output;
    for (const segment of segments.slice(0, -1)) {
      if (!parent || typeof parent !== 'object' || !Object.hasOwn(parent, segment)) {
        parent = undefined;
        break;
      }
      parent = (parent as Record<string, unknown>)[segment];
    }
    const key = segments.at(-1)!;
    if (
      ![
        'title',
        'label',
        'subtitle',
        'emptyDescription',
        'searchPlaceholder',
        'recordLabel',
        'fallbackTitle',
        'createTitle',
        'treeRootTitle',
        'groupByTitle',
        'sumFieldTitle',
        'trueLabel',
        'falseLabel',
        'message',
        'text',
      ].includes(key)
    )
      continue;
    if (
      parent &&
      typeof parent === 'object' &&
      Object.hasOwn(parent, key) &&
      typeof (parent as Record<string, unknown>)[key] === 'string'
    )
      (parent as Record<string, unknown>)[key] = textOf(text);
  }
  return output;
}

export function isTextReference(value: unknown): value is TextReference {
  if (!value || typeof value !== 'object') return false;
  const source = value as Record<string, unknown>;
  return (
    ['CODE', 'CONFIG'].includes(String(source.sourceKind)) &&
    typeof source.sourceId === 'string' &&
    Boolean(source.sourceId.trim()) &&
    typeof source.sourceVersion === 'string' &&
    Boolean(source.sourceVersion.trim()) &&
    typeof source.originalText === 'string'
  );
}

/** A label is valid only while its explicitly declared source still matches the raw record value. */
export function recordPresentationText(
  record: object | undefined,
  field: string,
): TextPresentation | undefined {
  if (!record) return undefined;
  const values = record as Record<string, unknown>;
  const texts = values.$texts;
  if (!Object.hasOwn(values, field) || !texts || typeof texts !== 'object') return undefined;
  const text = (texts as Record<string, TextPresentation>)[field];
  return text &&
    isTextReference(text.source) &&
    text.languages &&
    typeof values[field] === 'string' &&
    text.source.originalText === values[field]
    ? text
    : undefined;
}

export function recordPresentationLabel(
  record: object,
  field: string,
  context: LanguageContext,
): string | undefined {
  const original = (record as Record<string, unknown>)[field];
  if (original == null) return undefined;
  return (
    businessContentLabel(String(original), recordBusinessContentText(record, field), context) ??
    presentationLabel(String(original), recordPresentationText(record, field), context)
  );
}

/** Removes only the record's read metadata; arbitrary nested JSON business values are preserved. */
export function withoutRecordPresentation<T>(record: T): T {
  if (
    !record ||
    typeof record !== 'object' ||
    (!Object.hasOwn(record, '$texts') && !Object.hasOwn(record, '$contentTexts'))
  )
    return record;
  const values = { ...(record as Record<string, unknown>) };
  delete values.$texts;
  delete values.$contentTexts;
  return values as T;
}

/** An edited original immediately invalidates its disposable reading snapshot. */
export function businessContentLabel(
  original: string,
  text: BusinessContentTextPresentation | undefined,
  context: LanguageContext,
): string | undefined {
  if (!text || text.originalText !== original || !text.readings) return undefined;
  const translated = text.readings[businessContentLanguageContext(context).languageTag.value];
  return typeof translated === 'string' && translated.trim() ? translated : undefined;
}

export function recordBusinessContentText(
  record: object | undefined,
  field: string,
): BusinessContentTextPresentation | undefined {
  if (!record) return undefined;
  const values = record as Record<string, unknown>;
  const texts = values.$contentTexts;
  if (!Object.hasOwn(values, field) || !texts || typeof texts !== 'object') return undefined;
  const text = (texts as Record<string, BusinessContentTextPresentation>)[field];
  return text &&
    !Array.isArray(text) &&
    typeof values[field] === 'string' &&
    text.originalText === values[field]
    ? text
    : undefined;
}

export function recordBusinessContentArray(
  record: Record<string, unknown>,
  field: string,
  context: LanguageContext,
): unknown {
  const originals = record[field];
  const texts = (record.$contentTexts as Record<string, unknown> | undefined)?.[field];
  if (!Array.isArray(originals) || !Array.isArray(texts) || originals.length !== texts.length)
    return originals;
  return originals.map((original, index) =>
    typeof original === 'string'
      ? (businessContentLabel(original, texts[index], context) ?? original)
      : original,
  );
}
