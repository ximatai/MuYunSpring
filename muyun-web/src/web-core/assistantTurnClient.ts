import type {
  AssistantSelectionInteraction,
  AssistantTurnInput,
  AssistantTurnOutput,
} from '@muyun/web-contracts';
import { AppError, platformErrorCodes } from './errors';
import { isHttpStreamClient, type HttpClient } from './http';
import { createSseParser } from './sse';

export interface AssistantTurnProgress {
  onTextDelta?(text: string): void;
}

export type AssistantTurnRequester = (
  input: AssistantTurnInput,
  signal: AbortSignal,
  progress?: AssistantTurnProgress,
) => Promise<AssistantTurnOutput>;

/** Uses the supplied transport as-is so page-owned tenant scope remains authoritative. */
export function createAssistantTurnRequester(http: HttpClient): AssistantTurnRequester {
  if (!isHttpStreamClient(http)) {
    return async (input, signal) =>
      parseTurnOutput(
        await http.request<unknown>({
          method: 'POST',
          path: '/platform.assistant/turn',
          body: input,
          signal,
        }),
      );
  }
  return async (input, signal, progress) => {
    const stream = await http.stream({
      method: 'POST',
      path: '/platform.assistant/turn/stream',
      body: input,
      signal,
      headers: { Accept: 'text/event-stream, application/json' },
    });
    return consumeAssistantTurnStream(stream, progress);
  };
}

async function consumeAssistantTurnStream(
  stream: ReadableStream<Uint8Array>,
  progress: AssistantTurnProgress | undefined,
) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let output: AssistantTurnOutput | undefined;
  let streamError: AppError | undefined;
  const parser = createSseParser(({ event, data }) => {
    const value = parseEventData(data);
    if (event === 'text') {
      const text = field(value, 'text');
      if (typeof text !== 'string') throw invalidResponse();
      progress?.onTextDelta?.(text);
    } else if (event === 'complete') {
      output = parseTurnOutput(value);
    } else if (event === 'error') {
      streamError = parseStreamError(value);
    }
  });
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      parser.push(decoder.decode(value, { stream: true }));
    }
    parser.push(decoder.decode());
    parser.finish();
  } catch (error) {
    await reader.cancel(error).catch(() => undefined);
    throw error;
  } finally {
    reader.releaseLock();
  }
  if (streamError) throw streamError;
  if (!output) throw invalidResponse();
  return output;
}

function parseStreamError(value: unknown) {
  const message = field(value, 'message');
  return new AppError(typeof message === 'string' && message.trim() ? message : '智能助手响应中断，请重试', {
    code: stringField(value, 'code') ?? platformErrorCodes.appError,
    status: numberField(value, 'status'),
    traceId: stringField(value, 'traceId'),
    scope: recordField(value, 'scope'),
    details: recordField(value, 'details'),
    messageArgs: recordField(value, 'messageArgs'),
  });
}

function parseEventData(data: string): unknown {
  try {
    return JSON.parse(data);
  } catch {
    throw invalidResponse();
  }
}

function parseTurnOutput(value: unknown): AssistantTurnOutput {
  if (!isRecord(value) || !Array.isArray(value.toolCalls)) throw invalidResponse();
  if (value.text !== undefined && value.text !== null && typeof value.text !== 'string')
    throw invalidResponse();
  if (
    value.finishReason !== undefined &&
    value.finishReason !== null &&
    typeof value.finishReason !== 'string'
  ) {
    throw invalidResponse();
  }
  if (value.requestId !== undefined && value.requestId !== null && typeof value.requestId !== 'string') {
    throw invalidResponse();
  }
  for (const call of value.toolCalls) {
    if (
      !isRecord(call) ||
      typeof call.id !== 'string' ||
      typeof call.code !== 'string' ||
      !('input' in call)
    ) {
      throw invalidResponse();
    }
  }
  const selection =
    value.selection === undefined || value.selection === null ? undefined : parseSelection(value.selection);
  return {
    ...(typeof value.text === 'string' ? { text: value.text } : {}),
    toolCalls: value.toolCalls as AssistantTurnOutput['toolCalls'],
    ...(selection ? { selection } : {}),
    ...(typeof value.finishReason === 'string' ? { finishReason: value.finishReason } : {}),
    ...(typeof value.requestId === 'string' ? { requestId: value.requestId } : {}),
  };
}

function parseSelection(value: unknown): AssistantSelectionInteraction {
  if (!isRecord(value) || !Array.isArray(value.options)) throw invalidResponse();
  const interactionId = value.interactionId;
  const prompt = value.prompt;
  const inputPolicy = value.inputPolicy;
  const presentation = value.presentation;
  if (
    typeof interactionId !== 'string' ||
    !interactionId ||
    interactionId.length > 256 ||
    typeof prompt !== 'string' ||
    !prompt ||
    prompt.length > 500 ||
    !['free_text_allowed', 'selection_required'].includes(String(inputPolicy)) ||
    !['options', 'confirmation'].includes(String(presentation)) ||
    value.options.length < 2 ||
    value.options.length > 8
  ) {
    throw invalidResponse();
  }
  const options = value.options.map((option) => {
    if (
      !isRecord(option) ||
      typeof option.id !== 'string' ||
      !/^[A-Za-z0-9._:-]{1,64}$/.test(option.id) ||
      typeof option.label !== 'string' ||
      !option.label ||
      option.label.length > 80
    ) {
      throw invalidResponse();
    }
    return { id: option.id, label: option.label };
  });
  if (new Set(options.map(({ id }) => id)).size !== options.length) throw invalidResponse();
  if (
    presentation === 'confirmation' &&
    (inputPolicy !== 'selection_required' || options.map(({ id }) => id).join(',') !== 'confirm,cancel')
  ) {
    throw invalidResponse();
  }
  return {
    interactionId,
    prompt,
    inputPolicy: inputPolicy as AssistantSelectionInteraction['inputPolicy'],
    presentation: presentation as AssistantSelectionInteraction['presentation'],
    options,
  };
}

function field(value: unknown, name: string) {
  return isRecord(value) ? value[name] : undefined;
}

function stringField(value: unknown, name: string) {
  const candidate = field(value, name);
  return typeof candidate === 'string' ? candidate : undefined;
}

function numberField(value: unknown, name: string) {
  const candidate = field(value, name);
  return typeof candidate === 'number' ? candidate : undefined;
}

function recordField(value: unknown, name: string) {
  const candidate = field(value, name);
  return isRecord(candidate) ? candidate : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function invalidResponse() {
  return new AppError('智能助手返回了无效响应', { code: platformErrorCodes.networkError });
}
