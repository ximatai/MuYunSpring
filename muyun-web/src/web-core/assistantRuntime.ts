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

interface InternalAssistantRuntimeStepResult extends AssistantRuntimeStepResult {
  attemptedCallCount: number;
  continuationToken?: AssistantInvocationToken;
  replayableCalls: Map<string, AssistantCapabilityResult>;
}

export interface AssistantConversationOptions {
  signal?: AbortSignal;
  maxSteps?: number;
  onStep?(step: AssistantRuntimeStepResult): void | Promise<void>;
}

export interface AssistantConversationResult {
  steps: AssistantRuntimeStepResult[];
  completed: boolean;
}

const DEFAULT_MAX_STEPS = 8;
const MAX_DECISION_RESTARTS = 3;

class AssistantDecisionContextChangedError extends Error {
  constructor(readonly token: AssistantInvocationToken) {
    super('Assistant decision context changed before capability execution');
    this.name = 'AssistantDecisionContextChangedError';
  }
}

/**
 * Runs the bounded tool loop for one user message. Every step takes a fresh
 * Surface snapshot so navigation and draft changes are observed before the
 * model decides its next action.
 */
export async function runAssistantConversation(
  registry: AssistantSurfaceRegistry,
  message: string,
  options: AssistantConversationOptions = {},
): Promise<AssistantConversationResult> {
  const maxSteps = options.maxSteps ?? DEFAULT_MAX_STEPS;
  if (!Number.isInteger(maxSteps) || maxSteps < 1 || maxSteps > DEFAULT_MAX_STEPS) {
    throw new Error(`Assistant conversation maxSteps must be between 1 and ${DEFAULT_MAX_STEPS}`);
  }
  const steps: AssistantRuntimeStepResult[] = [];
  let results: AssistantCapabilityResult[] = [];
  let successfulCalls = new Map<string, AssistantCapabilityResult>();
  let expectedReplacementToken: AssistantInvocationToken | undefined;
  let decisionRestarts = 0;
  for (let index = 0; index < maxSteps; index += 1) {
    let step: InternalAssistantRuntimeStepResult;
    try {
      step = await runAssistantStepWithSuccessfulCalls(
        registry,
        message,
        results,
        options.signal,
        successfulCalls,
      );
    } catch (error) {
      const replacement = registry.snapshot()?.token;
      if (
        error instanceof AssistantDecisionContextChangedError &&
        expectedReplacementToken !== undefined &&
        sameToken(error.token, expectedReplacementToken) &&
        isSamePageSurfaceReplacement(expectedReplacementToken, replacement) &&
        decisionRestarts < MAX_DECISION_RESTARTS
      ) {
        decisionRestarts += 1;
        expectedReplacementToken = replacement;
        index -= 1;
        await delayForSurfaceReplacement(options.signal);
        continue;
      }
      if (error instanceof AssistantDecisionContextChangedError) {
        throw new StaleAssistantInvocationError();
      }
      throw error;
    }
    decisionRestarts = 0;
    expectedReplacementToken = step.continuationToken;
    successfulCalls = step.replayableCalls;
    const publicStep = toPublicStep(step);
    steps.push(publicStep);
    if (step.output.toolCalls.length === 0) {
      await options.onStep?.(publicStep);
      return { steps, completed: true };
    }
    if (step.attemptedCallCount === 0) {
      await options.onStep?.({ ...publicStep, results: [] });
      return { steps, completed: true };
    }
    await options.onStep?.(publicStep);
    results = step.results;
  }
  return { steps, completed: false };
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
  return toPublicStep(
    await runAssistantStepWithSuccessfulCalls(registry, message, previousResults, signal, new Map()),
  );
}

function toPublicStep(step: InternalAssistantRuntimeStepResult): AssistantRuntimeStepResult {
  return {
    output: step.output,
    results: step.results,
    contextChanged: step.contextChanged,
  };
}

async function runAssistantStepWithSuccessfulCalls(
  registry: AssistantSurfaceRegistry,
  message: string,
  previousResults: AssistantCapabilityResult[],
  signal: AbortSignal | undefined,
  successfulCalls: Map<string, AssistantCapabilityResult>,
): Promise<InternalAssistantRuntimeStepResult> {
  const snapshot = registry.snapshot();
  if (!snapshot) throw new Error('No assistant surface is active');
  let output: AssistantTurnOutput;
  try {
    output = await registry.requestTurn({ message, results: previousResults }, snapshot.token, signal);
  } catch (error) {
    if (!signal?.aborted && (error instanceof StaleAssistantInvocationError || isAbortError(error))) {
      throw new AssistantDecisionContextChangedError(snapshot.token);
    }
    throw error;
  }
  if (output.toolCalls.length > MAX_CALLS_PER_STEP) {
    throw new Error(`Assistant returned more than ${MAX_CALLS_PER_STEP} capability calls`);
  }
  const results: AssistantCapabilityResult[] = [];
  const replayableCalls = new Map<string, AssistantCapabilityResult>();
  let attemptedCallCount = 0;
  for (const call of output.toolCalls) {
    const callKey = capabilityCallKey(snapshot.token, call.code, call.input);
    const successful = successfulCalls.get(callKey);
    if (successful) {
      results.push({ ...successful, callId: call.id });
      replayableCalls.set(callKey, successful);
      continue;
    }
    attemptedCallCount += 1;
    try {
      const invocation = await registry.invoke(call, snapshot.token, signal);
      const result = { callId: call.id, capabilityCode: call.code, output: invocation.value };
      results.push(result);
      replayableCalls.set(callKey, result);
      if (invocation.contextChanged) {
        const continuationToken = registry.snapshot()?.token;
        if (continuationToken) {
          replayableCalls.set(capabilityCallKey(continuationToken, call.code, call.input), result);
        }
        return {
          output,
          results,
          contextChanged: true,
          attemptedCallCount,
          continuationToken,
          replayableCalls,
        };
      }
    } catch (error) {
      if (error instanceof StaleAssistantInvocationError || isAbortError(error)) throw error;
      results.push({
        callId: call.id,
        capabilityCode: call.code,
        error: {
          code: 'CAPABILITY_FAILED',
          message: 'Capability execution failed',
        },
      });
    }
    if (!sameToken(snapshot.token, registry.snapshot()?.token)) {
      return { output, results, contextChanged: true, attemptedCallCount, replayableCalls };
    }
  }
  return { output, results, contextChanged: false, attemptedCallCount, replayableCalls };
}

function capabilityCallKey(token: AssistantInvocationToken, code: string, input: unknown) {
  return `${token.pageInstanceKey}:${token.surfaceGeneration}:${token.contextRevision}:${code}:${JSON.stringify(
    canonicalCapabilityInput(input),
  )}`;
}

function canonicalCapabilityInput(input: unknown): unknown {
  if (Array.isArray(input)) return input.map(canonicalCapabilityInput);
  if (!input || typeof input !== 'object') return input;
  return Object.fromEntries(
    Object.entries(input as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, value]) => [key, canonicalCapabilityInput(value)]),
  );
}

function sameToken(left: AssistantInvocationToken, right: AssistantInvocationToken | undefined) {
  return (
    right !== undefined &&
    left.pageInstanceKey === right.pageInstanceKey &&
    left.surfaceGeneration === right.surfaceGeneration &&
    left.contextRevision === right.contextRevision &&
    left.fallback === right.fallback
  );
}

function isSamePageSurfaceReplacement(
  previous: AssistantInvocationToken,
  current: AssistantInvocationToken | undefined,
) {
  return (
    current !== undefined &&
    previous.pageInstanceKey === current.pageInstanceKey &&
    previous.surfaceGeneration !== current.surfaceGeneration &&
    previous.fallback &&
    !current.fallback
  );
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError';
}

function delayForSurfaceReplacement(signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Assistant invocation was cancelled', 'AbortError'));
      return;
    }
    const finish = () => {
      signal?.removeEventListener('abort', abort);
      resolve();
    };
    const abort = () => {
      globalThis.clearTimeout(timeout);
      reject(new DOMException('Assistant invocation was cancelled', 'AbortError'));
    };
    const timeout = globalThis.setTimeout(finish, 50);
    signal?.addEventListener('abort', abort, { once: true });
  });
}
