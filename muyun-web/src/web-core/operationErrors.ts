import type { TextReference } from '@muyun/web-contracts';
import { platformLanguageContext, type LanguageContext } from './language';
import { sha256Hex } from './sha256';

export interface OperationErrorMessage {
  readonly message: string;
  readonly source: TextReference;
  readonly args: Readonly<Record<string, string | number | boolean | null>>;
}

/** Explicit CODE declaration; parameters are frozen display facts, never inferred from prose. */
export function codeOperationErrorMessage(
  language: LanguageContext,
  key: string,
  original: string,
  args: Record<string, unknown> = {},
): OperationErrorMessage {
  const parameters = Object.fromEntries(
    Object.entries(args).map(([name, value]) => [
      name,
      value === null || ['string', 'number', 'boolean'].includes(typeof value)
        ? (value as string | number | boolean | null)
        : String(value ?? ''),
    ]),
  );
  return {
    message: language.t(key, original, parameters),
    source: {
      sourceKind: 'CODE',
      sourceId: key,
      sourceVersion: `sha256-${sha256Hex(original)}`,
      originalText: original,
    },
    args: Object.freeze(parameters),
  };
}

/** A rejected editor operation with safe, bounded feedback for callers. */
export class OperationUsageError extends Error {
  readonly textMessage?: OperationErrorMessage;
  constructor(
    message: string | OperationErrorMessage,
    readonly code:
      | 'CAPABILITY_USAGE_INVALID'
      | 'CANDIDATE_AMBIGUOUS'
      | 'CANDIDATE_EXPIRED'
      | 'PRECONDITION_FAILED' = 'CAPABILITY_USAGE_INVALID',
  ) {
    super(typeof message === 'string' ? message : message.message);
    this.textMessage =
      typeof message === 'string'
        ? undefined
        : Object.freeze({
            ...message,
            source: Object.freeze({ ...message.source }),
            args: Object.freeze({ ...message.args }),
          });
    this.name = 'OperationUsageError';
  }
}

/** Resolves owned presentation facts without replaying validation or changing the error code. */
export function resolveOperationErrorMessage(
  error: Error & { readonly textMessage?: OperationErrorMessage },
  language: LanguageContext = platformLanguageContext,
): string {
  const text = error.textMessage;
  return text &&
    text.source.sourceKind === 'CODE' &&
    text.source.sourceVersion === `sha256-${sha256Hex(text.source.originalText)}`
    ? language.t(text.source.sourceId, text.source.originalText, text.args)
    : error.message;
}

/** A staged draft was rejected before publication; no draft or display state was changed. */
export class DraftUpdateRejectedError extends OperationUsageError {
  constructor(failure: OperationUsageError) {
    super(failure.textMessage ?? failure.message, failure.code);
    this.name = 'DraftUpdateRejectedError';
  }
}
