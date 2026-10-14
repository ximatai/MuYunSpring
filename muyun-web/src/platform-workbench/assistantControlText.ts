import type { AssistantControlText, LanguageContext } from '@muyun/web-core';
import { platformCodeTextOriginal } from '@muyun/web-core';

type Argument = string | number | boolean | null | ControlText;
/** The author supplies CODE identity and original template, never inferred from rendered prose. */
export type ControlText =
  | { code: string; original: string; args?: Record<string, Argument> }
  | { parts: Array<string | ControlText> };

export const codeControlText = (
  code: string,
  original: string,
  args?: Record<string, Argument>,
): ControlText => ({ code, original, ...(args ? { args } : {}) });
export const joinControlText = (...parts: Array<string | ControlText>): ControlText => ({ parts });

export function modelControlOriginal(code: string): string | undefined {
  return /^platform\.error\.AI_[A-Z_]+$/.test(code) ? platformCodeTextOriginal(code) : undefined;
}
function owned(code: string) {
  return (
    code.startsWith('platform.screen.useAssistantConversation.') || /^platform\.error\.AI_[A-Z_]+$/.test(code)
  );
}
export function resolveControlText(text: ControlText, language: LanguageContext): string {
  if ('parts' in text)
    return text.parts
      .map((part) => (typeof part === 'string' ? part : resolveControlText(part, language)))
      .join('');
  return language.t(
    text.code,
    text.original,
    Object.fromEntries(
      Object.entries(text.args ?? {}).map(([key, value]) => [
        key,
        typeof value === 'object' && value !== null ? resolveControlText(value, language) : value,
      ]),
    ),
  );
}

async function versionOf(original: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(original));
  return (
    'sha256-' + Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
  );
}
/** Materializes immutable references without persisting closures, errors or translated argument fragments. */
export async function archiveControlText(text: ControlText): Promise<AssistantControlText> {
  if ('parts' in text)
    return {
      parts: await Promise.all(
        text.parts.map((part) => (typeof part === 'string' ? part : archiveControlText(part))),
      ),
    };
  if (!owned(text.code) || platformCodeTextOriginal(text.code) !== text.original)
    throw new Error('Unregistered assistant control text source');
  const args =
    text.args &&
    Object.fromEntries(
      await Promise.all(
        Object.entries(text.args).map(async ([key, value]) => [
          key,
          typeof value === 'object' && value !== null ? await archiveControlText(value) : value,
        ]),
      ),
    );
  return {
    source: {
      sourceKind: 'CODE',
      sourceId: text.code,
      sourceVersion: await versionOf(text.original),
      originalText: text.original,
    },
    ...(args ? { args } : {}),
  };
}

/** Unknown or changed nested sources invalidate the whole projection; the transcript keeps its original text. */
export async function restoreControlText(input: AssistantControlText): Promise<ControlText | undefined> {
  let nodes = 0;
  let sources = 0;
  async function read(value: unknown, depth: number): Promise<ControlText> {
    if (++nodes > 64 || depth > 4 || !value || typeof value !== 'object' || Array.isArray(value))
      throw new Error('Invalid control text');
    const record = value as Record<string, unknown>;
    if ('parts' in record) {
      if (
        Object.keys(record).some((key) => key !== 'parts') ||
        !Array.isArray(record.parts) ||
        record.parts.length > 32
      )
        throw new Error('Invalid control text parts');
      const parts = await Promise.all(
        record.parts.map((part) => {
          if (typeof part === 'string') {
            if (part.length > 4000) throw new Error('Oversized control text');
            return part;
          }
          return read(part, depth + 1);
        }),
      );
      return { parts };
    }
    if (Object.keys(record).some((key) => !['source', 'args'].includes(key)))
      throw new Error('Invalid control text properties');
    const source = record.source as Record<string, unknown> | undefined;
    if (
      !source ||
      typeof source !== 'object' ||
      Array.isArray(source) ||
      Object.keys(source).some(
        (key) => !['sourceKind', 'sourceId', 'sourceVersion', 'originalText'].includes(key),
      ) ||
      source.sourceKind !== 'CODE' ||
      typeof source.sourceId !== 'string' ||
      !owned(source.sourceId) ||
      typeof source.originalText !== 'string' ||
      source.originalText.length > 4000 ||
      source.sourceVersion !== (await versionOf(source.originalText))
    )
      throw new Error('Invalid control text source');
    const frozen = `${source.sourceId}@${source.sourceVersion}`;
    const code =
      platformCodeTextOriginal(source.sourceId) === source.originalText
        ? source.sourceId
        : platformCodeTextOriginal(frozen) === source.originalText
          ? frozen
          : undefined;
    if (!code) throw new Error('Unknown control text version');
    sources++;
    const rawArgs = record.args;
    if (
      rawArgs !== undefined &&
      (!rawArgs || typeof rawArgs !== 'object' || Array.isArray(rawArgs) || Object.keys(rawArgs).length > 16)
    )
      throw new Error('Invalid control text arguments');
    const args =
      rawArgs &&
      Object.fromEntries(
        await Promise.all(
          Object.entries(rawArgs).map(async ([key, value]) => {
            if (!/^[a-zA-Z][a-zA-Z0-9_]{0,63}$/.test(key))
              throw new Error('Invalid control text argument name');
            if (
              value === null ||
              typeof value === 'boolean' ||
              (typeof value === 'number' && Number.isFinite(value)) ||
              (typeof value === 'string' && value.length <= 4000)
            )
              return [key, value];
            return [key, await read(value, depth + 1)];
          }),
        ),
      );
    return codeControlText(code, source.originalText, args || undefined);
  }
  try {
    if (JSON.stringify(input).length > 32000) return undefined;
    const text = await read(input, 0);
    return sources ? text : undefined;
  } catch {
    return undefined;
  }
}
