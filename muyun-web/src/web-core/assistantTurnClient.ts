import type { AssistantTurnInput, AssistantTurnOutput } from '@muyun/web-contracts';
import type { HttpClient } from './http';

export type AssistantTurnRequester = (
  input: AssistantTurnInput,
  signal: AbortSignal,
) => Promise<AssistantTurnOutput>;

/** Uses the supplied transport as-is so page-owned tenant scope remains authoritative. */
export function createAssistantTurnRequester(http: HttpClient): AssistantTurnRequester {
  return (input, signal) =>
    http.request<AssistantTurnOutput>({
      method: 'POST',
      path: '/platform.assistant/turn',
      body: input,
      signal,
    });
}
