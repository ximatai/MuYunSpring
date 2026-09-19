import type { AssistantCapabilityResult, AssistantTurnOutput } from '@muyun/web-contracts';
import {
  StaleAssistantInvocationError,
  type AssistantInvocationToken,
  type AssistantSurfaceRegistry,
} from './assistantSurface';

const MAX_CALLS_PER_STEP = 8;

export interface AssistantRuntimeStepResult {
  output: AssistantTurnOutput;
  results: AssistantCapabilityResult[];
  contextChanged: boolean;
}

/**
 * Executes one bounded model step. A context-changing capability ends the step;
 * the caller starts the next turn from a fresh Surface snapshot.
 */
export async function runAssistantStep(
  registry: AssistantSurfaceRegistry,
  message: string,
  previousResults: AssistantCapabilityResult[] = [],
  signal?: AbortSignal,
): Promise<AssistantRuntimeStepResult> {
  const snapshot = registry.snapshot();
  if (!snapshot) throw new Error('No assistant surface is active');
  const output = await registry.requestTurn({ message, results: previousResults }, snapshot.token, signal);
  if (output.toolCalls.length > MAX_CALLS_PER_STEP) {
    throw new Error(`Assistant returned more than ${MAX_CALLS_PER_STEP} capability calls`);
  }
  const results: AssistantCapabilityResult[] = [];
  for (const call of output.toolCalls) {
    try {
      const invocation = await registry.invoke(call, snapshot.token, signal);
      results.push({ callId: call.id, output: invocation.value });
      if (invocation.contextChanged) return { output, results, contextChanged: true };
    } catch (error) {
      if (error instanceof StaleAssistantInvocationError || isAbortError(error)) throw error;
      results.push({
        callId: call.id,
        error: {
          code: 'CAPABILITY_FAILED',
          message: 'Capability execution failed',
        },
      });
    }
    if (!sameToken(snapshot.token, registry.snapshot()?.token)) {
      return { output, results, contextChanged: true };
    }
  }
  return { output, results, contextChanged: false };
}

function sameToken(left: AssistantInvocationToken, right: AssistantInvocationToken | undefined) {
  return (
    right !== undefined &&
    left.pageInstanceKey === right.pageInstanceKey &&
    left.surfaceGeneration === right.surfaceGeneration &&
    left.contextRevision === right.contextRevision
  );
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError';
}
