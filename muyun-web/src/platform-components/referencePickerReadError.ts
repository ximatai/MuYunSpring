import { AppError, normalizeError, platformErrorCodes } from '@muyun/web-core';

export type ReferencePickerReadErrorKind =
  | 'accessDenied'
  | 'missingDependencies'
  | 'unsupportedConfiguration'
  | 'temporaryFailure';

/**
 * The picker only classifies facts carried by AppError or facts its source can
 * prove locally. It deliberately never derives a category from an error message.
 */
export class ReferencePickerReadError extends AppError {
  readonly retryable: boolean;
  readonly missingDependencyFields: readonly string[];
  readonly original: AppError;

  constructor(
    readonly kind: ReferencePickerReadErrorKind,
    message: string,
    options: {
      retryable: boolean;
      missingDependencyFields?: readonly string[];
      original?: AppError;
    },
  ) {
    const original = options.original ?? new AppError(message, { code: platformErrorCodes.appError });
    super(message, {
      code: original.code,
      status: original.status,
      traceId: original.traceId,
      scope: original.scope,
      targets: original.targets,
      details: original.details,
      messageArgs: original.messageArgs,
      actionMessage: original.actionMessage,
    });
    this.name = 'ReferencePickerReadError';
    this.retryable = options.retryable;
    this.missingDependencyFields = options.missingDependencyFields ?? [];
    this.original = original;
    this.globallyHandled = original.globallyHandled;
  }
}

/**
 * Returns undefined for 401 so the request client's authentication lifecycle
 * remains the sole owner of login recovery and redirect behavior.
 */
export function referencePickerReadErrorOf(
  cause: unknown,
  fallbackMessage: string,
): ReferencePickerReadError | undefined {
  if (cause instanceof ReferencePickerReadError) return cause;

  const error = normalizeError(cause);
  if (error.status === 401) return undefined;
  if (error.status === 403 || error.code === platformErrorCodes.accessDenied) {
    return new ReferencePickerReadError('accessDenied', error.message || fallbackMessage, {
      retryable: false,
      original: error,
    });
  }
  if (error.code === platformErrorCodes.configMissing) {
    return new ReferencePickerReadError('unsupportedConfiguration', error.message || fallbackMessage, {
      retryable: false,
      original: error,
    });
  }
  return new ReferencePickerReadError('temporaryFailure', error.message || fallbackMessage, {
    retryable: true,
    original: error,
  });
}

export function missingReferencePickerDependencies(
  sourceFields: readonly string[],
): ReferencePickerReadError {
  const fields = [...new Set(sourceFields)];
  const message = '请先补齐引用所需的依赖字段';
  return new ReferencePickerReadError('missingDependencies', message, {
    retryable: false,
    missingDependencyFields: fields,
    original: new AppError(message, {
      code: platformErrorCodes.configMissing,
      details: { missingDependencyFields: fields },
    }),
  });
}

export function unsupportedReferencePickerConfiguration(message: string): ReferencePickerReadError {
  return new ReferencePickerReadError('unsupportedConfiguration', message, {
    retryable: false,
    original: new AppError(message, { code: platformErrorCodes.configMissing }),
  });
}
