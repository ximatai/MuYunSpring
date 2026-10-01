import type { WebActionMessage } from '@muyun/web-contracts';

export interface ErrorTarget {
  kind?: string;
  moduleAlias?: string;
  entityAlias?: string;
  relationAlias?: string;
  fieldName?: string;
  rowIndex?: number;
  recordId?: string;
  actionCode?: string;
  attachmentId?: string;
}

export interface ErrorUiContext {
  phase: 'page-load' | 'action' | 'background';
  surface: 'workbench' | 'form' | 'table' | 'dialog' | 'unknown';
}

export type GlobalErrorSlot = 'redirect-login' | 'page-error' | 'global-toast' | 'global-modal' | 'silent';

export interface GlobalErrorPresentation {
  slot: GlobalErrorSlot;
  message: string;
  traceId?: string;
}

export const platformErrorCodes = {
  appError: 'APP_ERROR',
  networkError: 'NETWORK_ERROR',
  httpError: 'HTTP_ERROR',
  authRequired: 'AUTH_REQUIRED',
  authExpired: 'AUTH_EXPIRED',
  passwordChangeRequired: 'PASSWORD_CHANGE_REQUIRED',
  loginBadCredentials: 'LOGIN_BAD_CREDENTIALS',
  accessDenied: 'ACCESS_DENIED',
  validationFailed: 'VALIDATION_FAILED',
  conflictVersion: 'CONFLICT_VERSION',
  resourceInUse: 'RESOURCE_IN_USE',
  resourceNotFound: 'RESOURCE_NOT_FOUND',
  resourceSoftDeletedConflict: 'RESOURCE_SOFT_DELETED_CONFLICT',
  configMissing: 'CONFIG_MISSING',
  internalError: 'INTERNAL_ERROR',
} as const;

export type PlatformErrorCode = (typeof platformErrorCodes)[keyof typeof platformErrorCodes] | (string & {});

export class AppError extends Error {
  readonly code: PlatformErrorCode;
  readonly status?: number;
  readonly traceId?: string;
  readonly scope?: Record<string, unknown>;
  readonly targets: ErrorTarget[];
  readonly details?: Record<string, unknown>;
  readonly messageArgs?: Record<string, unknown>;
  readonly actionMessage?: WebActionMessage;
  globallyHandled = false;

  constructor(
    message: string,
    options: {
      code?: PlatformErrorCode;
      status?: number;
      traceId?: string;
      scope?: Record<string, unknown>;
      targets?: ErrorTarget[];
      details?: Record<string, unknown>;
      messageArgs?: Record<string, unknown>;
      actionMessage?: WebActionMessage;
    } = {},
  ) {
    super(message);
    this.name = 'AppError';
    this.code = options.code ?? platformErrorCodes.appError;
    this.status = options.status;
    this.traceId = options.traceId;
    this.scope = options.scope;
    this.targets = options.targets ?? [];
    this.details = options.details;
    this.messageArgs = options.messageArgs;
    this.actionMessage = options.actionMessage;
  }
}

export function normalizeError(error: unknown): AppError {
  if (error instanceof AppError) {
    return error;
  }
  if (error instanceof Error) {
    return new AppError(error.message, { code: platformErrorCodes.appError });
  }
  return new AppError('Unknown error', { code: platformErrorCodes.appError, details: { cause: error } });
}

export function resolveGlobalErrorPresentation(
  error: AppError,
  context: ErrorUiContext = { phase: 'action', surface: 'unknown' },
): GlobalErrorPresentation {
  if (error.status === 401) {
    if (error.code === platformErrorCodes.loginBadCredentials) {
      return presentation('global-toast', error);
    }
    return presentation('redirect-login', error);
  }
  if (context.phase === 'background') {
    return presentation('silent', error);
  }
  if ((error.status === 403 || error.status === 404) && context.phase === 'page-load') {
    return presentation('page-error', error);
  }
  if (error.status !== undefined && error.status >= 500 && context.phase === 'page-load') {
    return presentation('page-error', error);
  }
  if (error.status === 409 && context.phase === 'action') {
    return presentation('global-modal', error);
  }
  return presentation('global-toast', error);
}

function presentation(slot: GlobalErrorSlot, error: AppError): GlobalErrorPresentation {
  return {
    slot,
    message: userFacingErrorMessage(error),
    traceId: error.traceId,
  };
}

export function userFacingErrorMessage(error: AppError) {
  if (error.code === platformErrorCodes.networkError) {
    return '暂时无法连接服务，请稍后重试；持续失败时请联系管理员检查连接。';
  }
  const modelMessage = modelFailureMessage(error);
  if (modelMessage) return modelMessage;
  if (isUnexpectedPlatformError(error)) {
    return '系统异常，操作未完成';
  }
  return error.message;
}

/** Fixed explanations for known model failures; never expose arbitrary upstream 5xx text. */
export function modelFailureMessage(error: AppError): string | undefined {
  const messages: Record<string, string> = {
    AI_PROVIDER_AUTHENTICATION_FAILED: '模型连接鉴权失败。请联系管理员检查凭据及模型访问权限。',
    AI_PROVIDER_RATE_LIMITED: '模型服务限制了本次请求。请稍后重试；持续失败时请管理员检查额度。',
    AI_PROVIDER_UNAVAILABLE: '模型服务暂时不可用，请稍后重试。',
    AI_PROVIDER_REQUEST_REJECTED: '模型服务拒绝了本次请求。请联系管理员检查模型配置与服务状态。',
    AI_MODEL_TIMEOUT: '等待模型回复超时，请稍后重试。',
    AI_MODEL_CONNECTION_FAILED: '模型连接失败。请联系管理员检查网络与模型服务状态。',
    AI_MODEL_INCOMPLETE_RESPONSE: '模型回复在完成前断开。',
    AI_MODEL_INTERRUPTED: '模型请求已中断。',
    AI_MODEL_CALL_FAILED: '模型调用未能完成，请联系管理员检查模型服务状态。',
  };
  return Object.hasOwn(messages, error.code) ? messages[error.code] : undefined;
}

export function isUnexpectedPlatformError(error: AppError) {
  return error.code === platformErrorCodes.internalError || (error.status ?? 0) >= 500;
}
