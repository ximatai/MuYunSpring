import {
  normalizeError,
  resolveGlobalErrorPresentation,
  type AppError,
  type ErrorUiContext,
  type GlobalErrorPresentation,
} from '@muyun/web-core';
import {
  showErrorMessage,
  showInfoMessage,
  showSuccessMessage,
  showWarningMessage,
} from '@muyun/vue-ui-antdv';

export interface PlatformErrorFeedbackContext {
  source?: string;
  phase?: 'load' | 'action' | 'validation' | 'authorization';
  tone?: 'error' | 'success';
}

export function presentPlatformError(cause: unknown, context: PlatformErrorFeedbackContext = {}) {
  const error = normalizeError(cause);
  if (error.globallyHandled) {
    return error;
  }
  const presentation = resolveGlobalErrorPresentation(error, toErrorUiContext(context));
  presentGlobalErrorPresentation(presentation);
  return error;
}

export function presentPlatformMessage(message: string, context: PlatformErrorFeedbackContext = {}) {
  if (context.tone === 'success') {
    presentPlatformSuccess(message);
    return;
  }
  showErrorMessage(message);
}

export function presentPlatformSuccess(message: string, context: PlatformErrorFeedbackContext = {}) {
  void context;
  showSuccessMessage(message);
}

/** Presents a non-blocking recommendation without changing the requested action. */
export function presentPlatformWarning(message: string) {
  showWarningMessage(message);
}

/** Presents a non-blocking progress update without treating it as an action result. */
export function presentPlatformInfo(message: string) {
  showInfoMessage(message);
}

export type PlatformActionErrorHandler<TContext> = {
  code?: string;
  marker?: string;
  handle: (error: AppError, context: TContext) => void | boolean;
};

export function matchesPlatformActionErrorHandler<TContext>(
  error: AppError,
  handler: PlatformActionErrorHandler<TContext>,
) {
  if (handler.code && error.code === handler.code) {
    return true;
  }
  if (!handler.marker) {
    return false;
  }
  return (
    error.details?.marker === handler.marker ||
    error.details?.reason === handler.marker ||
    error.details?.errorKey === handler.marker ||
    error.message.includes(handler.marker)
  );
}

function toErrorUiContext(context: PlatformErrorFeedbackContext): ErrorUiContext {
  return {
    phase: context.phase === 'load' ? 'page-load' : 'action',
    surface: context.source?.includes('dialog') ? 'dialog' : 'unknown',
  };
}

function presentGlobalErrorPresentation(presentation: GlobalErrorPresentation) {
  if (presentation.slot === 'silent' || presentation.slot === 'redirect-login') {
    return;
  }
  showErrorMessage(presentation.message);
}
