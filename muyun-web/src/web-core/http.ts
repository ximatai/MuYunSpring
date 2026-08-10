import type { RouteQueryValue } from '@muyun/web-contracts';
import { AppError, platformErrorCodes, type ErrorTarget } from './errors';

export interface RequestContext {
  baseUrl?: string;
  token?: string;
  traceId?: string;
  credentials?: RequestCredentials;
  headers?: Record<string, string>;
  onAuthenticationRequired?: (error: AppError, token?: string) => boolean | void;
}

export interface HttpRequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string;
  query?: Record<string, RouteQueryValue>;
  body?: unknown;
  headers?: Record<string, string>;
}

export interface HttpClient {
  request<T>(options: HttpRequestOptions): Promise<T>;
  /** Opens an authenticated response stream without exposing transport credentials to consumers. */
  stream(options: HttpRequestOptions): Promise<ReadableStream<Uint8Array>>;
}

export function createHttpClient(context: RequestContext = {}): HttpClient {
  return {
    async request<T>(options: HttpRequestOptions): Promise<T> {
      let response: Response;
      try {
        response = await fetch(urlOf(context.baseUrl, options), {
          method: options.method ?? 'GET',
          credentials: context.credentials,
          headers: headersOf(context, options),
          body: options.body === undefined ? undefined : JSON.stringify(options.body),
        });
      } catch (error) {
        throw new AppError('Network request failed', {
          code: platformErrorCodes.networkError,
          details: { cause: error instanceof Error ? error.message : String(error) },
        });
      }

      if (!response.ok) {
        try {
          const error = await appErrorFromResponse(response);
          notifyAuthenticationRequired(context, error);
          throw error;
        } catch (error) {
          if (error instanceof AppError) {
            throw error;
          }
          throw new AppError(`Request failed with status ${response.status}`, {
            code: platformErrorCodes.httpError,
            status: response.status,
            traceId: response.headers.get('X-MuYun-Trace-Id') ?? undefined,
            details: { cause: error instanceof Error ? error.message : String(error) },
          });
        }
      }

      return (await responseBody(response)) as T;
    },
    async stream(options: HttpRequestOptions): Promise<ReadableStream<Uint8Array>> {
      let response: Response;
      try {
        response = await fetch(urlOf(context.baseUrl, options), {
          method: options.method ?? 'GET',
          credentials: context.credentials,
          // SSE endpoints participate in Spring MVC content negotiation. The
          // JSON default used by ordinary requests would reject this response
          // before the endpoint handler is invoked.
          headers: { ...headersOf(context, options), Accept: 'text/event-stream' },
          body: options.body === undefined ? undefined : JSON.stringify(options.body),
        });
      } catch (error) {
        throw new AppError('Network request failed', {
          code: platformErrorCodes.networkError,
          details: { cause: error instanceof Error ? error.message : String(error) },
        });
      }

      if (!response.ok) {
        const error = await appErrorFromResponse(response);
        notifyAuthenticationRequired(context, error);
        throw error;
      }
      if (!response.body) {
        throw new AppError('Response stream is unavailable', { code: platformErrorCodes.networkError });
      }
      return response.body;
    },
  };
}

function notifyAuthenticationRequired(context: RequestContext, error: AppError) {
  if (error.status !== 401 || error.code === platformErrorCodes.loginBadCredentials) {
    return;
  }
  error.globallyHandled = context.onAuthenticationRequired?.(error, context.token) === true;
}

function urlOf(baseUrl: string | undefined, options: HttpRequestOptions) {
  const base = baseUrl?.replace(/\/$/, '') ?? '';
  const path = options.path.startsWith('/') ? options.path : `/${options.path}`;
  const origin = typeof window === 'undefined' ? 'http://localhost' : window.location.origin;
  const url = new URL(`${base}${path}`, origin);
  Object.entries(options.query ?? {}).forEach(([key, value]) => {
    const values = Array.isArray(value) ? value : [value];
    for (const item of values) {
      if (item !== null && item !== undefined) {
        url.searchParams.append(key, String(item));
      }
    }
  });
  return url;
}

function headersOf(context: RequestContext, options: HttpRequestOptions) {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...context.headers,
  };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (context.token) {
    headers.Authorization = `Bearer ${context.token}`;
  }
  if (context.traceId) {
    headers['X-MuYun-Trace-Id'] = context.traceId;
  }
  Object.assign(headers, options.headers);
  return headers;
}

async function appErrorFromResponse(response: Response) {
  const details = await responseBody(response);
  const actionMessage = actionMessageOf(details);
  const message =
    actionMessage?.text ?? messageOf(details) ?? `Request failed with status ${response.status}`;
  const code = actionMessage?.code ?? codeOf(details) ?? platformErrorCodes.httpError;
  return new AppError(message, {
    code,
    status: response.status,
    traceId: traceIdOf(details) ?? response.headers.get('X-MuYun-Trace-Id') ?? undefined,
    scope: recordField(details, 'scope'),
    targets: targetsOf(details),
    details: recordField(details, 'details') ?? (isRecord(details) ? details : undefined),
    messageArgs: recordField(details, 'messageArgs') ?? actionMessage?.messageArgs,
    actionMessage,
  });
}

async function responseBody(response: Response) {
  if (response.status === 204) {
    return undefined;
  }
  const text = await response.text();
  if (!text) {
    return undefined;
  }
  const contentType = response.headers.get('content-type') ?? '';
  if (contentType.includes('application/json')) {
    return JSON.parse(text) as unknown;
  }
  return text;
}

function messageOf(details: unknown) {
  return objectField(details, 'message');
}

function codeOf(details: unknown) {
  return objectField(details, 'code');
}

function traceIdOf(details: unknown) {
  return objectField(details, 'traceId');
}

function actionMessageOf(details: unknown) {
  if (!isRecord(details) || !isRecord(details.actionMessage)) {
    return undefined;
  }
  const text = objectField(details.actionMessage, 'text');
  if (!text) {
    return undefined;
  }
  const code = objectField(details.actionMessage, 'code');
  const type = objectField(details.actionMessage, 'type');
  const messageArgs = recordField(details.actionMessage, 'messageArgs');
  return {
    text,
    code,
    type,
    ...(messageArgs ? { messageArgs } : {}),
  };
}

function targetsOf(details: unknown): ErrorTarget[] {
  if (!isRecord(details) || !Array.isArray(details.targets)) {
    return [];
  }
  return details.targets.filter(isRecord) as ErrorTarget[];
}

function recordField(value: unknown, key: string) {
  if (!isRecord(value) || !isRecord(value[key])) {
    return undefined;
  }
  return value[key] as Record<string, unknown>;
}

function objectField(value: unknown, key: string) {
  if (!isRecord(value) || !(key in value)) {
    return undefined;
  }
  const field = value[key];
  return typeof field === 'string' ? field : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}
