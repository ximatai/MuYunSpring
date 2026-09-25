import type {
  AssistantCapabilityResult,
  AssistantConversationMessage,
  AssistantSelectionResponse,
  AssistantTurnOutput,
} from '@muyun/web-contracts';
import {
  AssistantCapabilityUsageError,
  AssistantEffectInterruptedError,
  sameAssistantInvocationToken,
  StaleAssistantInvocationError,
  type AssistantInvocationToken,
  type AssistantSurfaceRegistry,
} from './assistantSurface';

const MAX_CALLS_PER_STEP = 8;

export interface AssistantRuntimeStepResult {
  output: AssistantTurnOutput;
  results: AssistantCapabilityResult[];
  contextChanged: boolean;
  /** Successful capability invocations that committed an effect through applyEffect in this step. */
  appliedEffectCount: number;
}

interface InternalAssistantRuntimeStepResult extends AssistantRuntimeStepResult {
  attemptedCallCount: number;
  continuationToken?: AssistantInvocationToken;
  replayableCalls: Map<string, AssistantCapabilityResult>;
}

export interface AssistantConversationOptions {
  signal?: AbortSignal;
  maxSteps?: number;
  /** Completed dialogue before the current user message. */
  history?: AssistantConversationMessage[];
  /** Structured answer to a selection shown by the immediately preceding assistant message. */
  selectionResponse?: AssistantSelectionResponse;
  onStep?(step: AssistantRuntimeStepResult): void | Promise<void>;
  onTextDelta?(text: string, stepIndex: number): void;
  onTextDiscard?(stepIndex: number): void;
  onActivity?(phase: AssistantActivityPhase, stepIndex: number): void;
  /** Receives content-free execution facts for local diagnostics or a governed telemetry adapter. */
  onDiagnostic?(event: AssistantRuntimeDiagnosticEvent): void | Promise<void>;
}

export type AssistantActivityPhase = 'understanding' | 'executing' | 'responding';

type AssistantDiagnosticSurface = 'workbench' | 'module-page' | 'metadata-governance' | 'other';
type AssistantDiagnosticFinishReason = 'stop' | 'tool_calls' | 'length' | 'content_filter' | 'other';

export type AssistantRuntimeDiagnosticEvent =
  | {
      type: 'decision.started';
      stepIndex: number;
      surface: AssistantDiagnosticSurface;
      backgroundContextRefreshed: boolean;
    }
  | {
      type: 'decision.completed';
      stepIndex: number;
      finishReason?: AssistantDiagnosticFinishReason;
      toolCallCount: number;
      hasText: boolean;
    }
  | {
      type: 'decision.restarted';
      stepIndex: number;
      attempt: number;
      reason: 'background-context-refresh' | 'formal-surface-ready';
    }
  | {
      type: 'decision.failed';
      stepIndex: number;
      reason: 'context-changed' | 'cancelled' | 'surface-settlement-failed' | 'model-request-failed';
    }
  | {
      type: 'capability.completed';
      stepIndex: number;
      capabilityCode: string;
      outcome: 'succeeded' | 'failed' | 'replayed';
      pageEffectApplied: boolean;
    }
  | {
      type: 'conversation.completed';
      stepCount: number;
      bounded: boolean;
    };

export interface AssistantConversationResult {
  steps: AssistantRuntimeStepResult[];
  termination: 'stopped' | 'waiting-for-user' | 'repeated-call' | 'step-limit';
}

export class AssistantConversationInterruptedError extends Error {
  constructor(
    readonly steps: readonly AssistantRuntimeStepResult[],
    cause: unknown,
    readonly termination:
      | 'cancelled'
      | 'context-changed'
      | 'execution-interrupted'
      | 'model-failed' = 'model-failed',
  ) {
    super('Assistant conversation interrupted with execution facts', { cause });
    this.name = 'AssistantConversationInterruptedError';
  }
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
  function waitForFormalSurface(token: AssistantInvocationToken) {
    return registry.waitForActiveSurface({
      pageInstanceKey: token.pageInstanceKey,
      requireFormal: true,
      signal: options.signal,
      timeoutMs: 15_000,
    });
  }
  let initial = registry.snapshot();
  const identityScope = initial?.token.identityScopeKey;
  if (initial?.token.conversationScopePending) initial = await waitForFormalSurface(initial.token);
  if (initial?.token.identityScopeKey !== identityScope) throw new StaleAssistantInvocationError();
  const conversationScope = initial?.token.conversationScopeKey;
  const maxSteps = options.maxSteps ?? DEFAULT_MAX_STEPS;
  if (!Number.isInteger(maxSteps) || maxSteps < 1 || maxSteps > DEFAULT_MAX_STEPS) {
    throw new Error(`Assistant conversation maxSteps must be between 1 and ${DEFAULT_MAX_STEPS}`);
  }
  const steps: AssistantRuntimeStepResult[] = [];
  let results: AssistantCapabilityResult[] = [];
  const completedEffects: AssistantCapabilityResult[] = [];
  let settledCalls = new Map<string, AssistantCapabilityResult>();
  let expectedReplacementToken: AssistantInvocationToken | undefined;
  let decisionRestarts = 0;
  for (let index = 0; index < maxSteps; index += 1) {
    let current = registry.snapshot();
    if (current?.token.conversationScopePending) current = await waitForFormalSurface(current.token);
    if (
      current?.token.identityScopeKey !== identityScope ||
      current?.token.conversationScopeKey !== conversationScope
    ) {
      throw new StaleAssistantInvocationError();
    }
    let step: InternalAssistantRuntimeStepResult;
    let streamedText = false;
    try {
      step = await runAssistantStepWithSettledCalls(
        registry,
        message,
        options.history ?? [],
        results,
        options.signal,
        settledCalls,
        index,
        index === 0 ? options.selectionResponse : undefined,
        options.onActivity,
        options.onDiagnostic,
        options.onTextDelta
          ? (text) => {
              streamedText = true;
              options.onTextDelta?.(text, index);
            }
          : undefined,
      );
    } catch (error) {
      if (streamedText) options.onTextDiscard?.(index);
      const replacement = registry.snapshot()?.token;
      const backgroundContextRefreshed =
        !hasAppliedCapabilityEffect(steps) && isSamePageSurfaceContextRefresh(error, replacement);
      const expectedSurfaceReplaced =
        expectedReplacementToken !== undefined &&
        error instanceof AssistantDecisionContextChangedError &&
        sameAssistantInvocationToken(error.token, expectedReplacementToken) &&
        isSamePageSurfaceReplacement(expectedReplacementToken, replacement);
      if (
        error instanceof AssistantDecisionContextChangedError &&
        (backgroundContextRefreshed || expectedSurfaceReplaced) &&
        decisionRestarts < MAX_DECISION_RESTARTS
      ) {
        decisionRestarts += 1;
        emitDiagnostic(options.onDiagnostic, {
          type: 'decision.restarted',
          stepIndex: index,
          attempt: decisionRestarts,
          reason: expectedSurfaceReplaced ? 'formal-surface-ready' : 'background-context-refresh',
        });
        expectedReplacementToken = replacement;
        index -= 1;
        await delayForSurfaceReplacement(options.signal);
        continue;
      }
      if (error instanceof AssistantDecisionContextChangedError) {
        emitDiagnostic(options.onDiagnostic, {
          type: 'decision.failed',
          stepIndex: index,
          reason: 'context-changed',
        });
        if (hasAppliedCapabilityEffect(steps))
          throw new AssistantConversationInterruptedError(steps, error, 'context-changed');
        throw new StaleAssistantInvocationError();
      }
      if (error instanceof AssistantConversationInterruptedError) {
        throw new AssistantConversationInterruptedError(
          [...steps, ...error.steps],
          error.cause,
          error.termination,
        );
      }
      if (hasAppliedCapabilityEffect(steps)) {
        throw new AssistantConversationInterruptedError(
          steps,
          error,
          isAbortError(error)
            ? 'cancelled'
            : error instanceof StaleAssistantInvocationError
              ? 'context-changed'
              : 'model-failed',
        );
      }
      throw error;
    }
    decisionRestarts = 0;
    expectedReplacementToken = step.continuationToken;
    settledCalls = step.replayableCalls;
    const publicStep = toPublicStep(step);
    steps.push(publicStep);
    if (step.output.toolCalls.length === 0) {
      await options.onStep?.(publicStep);
      emitDiagnostic(options.onDiagnostic, {
        type: 'conversation.completed',
        stepCount: steps.length,
        bounded: false,
      });
      return { steps, termination: step.output.selection ? 'waiting-for-user' : 'stopped' };
    }
    if (step.attemptedCallCount === 0) {
      await options.onStep?.({ ...publicStep, results: [] });
      emitDiagnostic(options.onDiagnostic, {
        type: 'conversation.completed',
        stepCount: steps.length,
        bounded: false,
      });
      return { steps, termination: 'repeated-call' };
    }
    await options.onStep?.(publicStep);
    // Receipts have conversation-local identities: providers may reuse call IDs on later turns.
    results = [...completedEffects.slice(-8), ...step.results].map(
      ({ callId, capabilityCode, input, execution, output, error }) => ({
        callId,
        capabilityCode,
        input,
        execution,
        ...(output !== undefined ? { output } : {}),
        ...(error ? { error } : {}),
      }),
    );
    if (step.appliedEffectCount > 0) {
      const effect = step.results.at(-1);
      if (effect && !effect.error)
        completedEffects.push({
          ...effect,
          callId: `receipt-${index}-${effect.callId}`,
          output: { completed: true },
          presentation: undefined,
        });
    }
  }
  emitDiagnostic(options.onDiagnostic, {
    type: 'conversation.completed',
    stepCount: steps.length,
    bounded: true,
  });
  return { steps, termination: 'step-limit' };
}

function hasAppliedCapabilityEffect(steps: readonly AssistantRuntimeStepResult[]) {
  return steps.some((step) => step.appliedEffectCount > 0);
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
    await runAssistantStepWithSettledCalls(registry, message, [], previousResults, signal, new Map()),
  );
}

function toPublicStep(step: InternalAssistantRuntimeStepResult): AssistantRuntimeStepResult {
  return {
    output: step.output,
    results: step.results,
    contextChanged: step.contextChanged,
    appliedEffectCount: step.appliedEffectCount,
  };
}

async function runAssistantStepWithSettledCalls(
  registry: AssistantSurfaceRegistry,
  message: string,
  history: AssistantConversationMessage[],
  previousResults: AssistantCapabilityResult[],
  signal: AbortSignal | undefined,
  settledCalls: Map<string, AssistantCapabilityResult>,
  stepIndex = 0,
  selectionResponse?: AssistantSelectionResponse,
  onActivity?: AssistantConversationOptions['onActivity'],
  onDiagnostic?: AssistantConversationOptions['onDiagnostic'],
  onTextDelta?: (text: string) => void,
): Promise<InternalAssistantRuntimeStepResult> {
  const initialSnapshot = registry.snapshot();
  if (!initialSnapshot) throw new Error('No assistant surface is active');
  let snapshot: typeof initialSnapshot;
  try {
    snapshot = await registry.settleActiveSurface(initialSnapshot.token, signal);
  } catch (error) {
    if (!signal?.aborted && (error instanceof StaleAssistantInvocationError || isAbortError(error))) {
      throw new AssistantDecisionContextChangedError(initialSnapshot.token);
    }
    emitDiagnostic(onDiagnostic, {
      type: 'decision.failed',
      stepIndex,
      reason: isAbortError(error) ? 'cancelled' : 'surface-settlement-failed',
    });
    throw error;
  }
  emitDiagnostic(onDiagnostic, {
    type: 'decision.started',
    stepIndex,
    surface: diagnosticSurface(snapshot.context.surface),
    backgroundContextRefreshed: initialSnapshot.token.contextRevision !== snapshot.token.contextRevision,
  });
  onActivity?.('understanding', stepIndex);
  let output: AssistantTurnOutput;
  try {
    output = onTextDelta
      ? await registry.requestTurn(
          { message, history, results: previousResults, ...(selectionResponse ? { selectionResponse } : {}) },
          snapshot.token,
          signal,
          {
            onTextDelta(text) {
              onActivity?.('responding', stepIndex);
              onTextDelta(text);
            },
          },
        )
      : await registry.requestTurn(
          { message, history, results: previousResults, ...(selectionResponse ? { selectionResponse } : {}) },
          snapshot.token,
          signal,
        );
  } catch (error) {
    if (!signal?.aborted && (error instanceof StaleAssistantInvocationError || isAbortError(error))) {
      throw new AssistantDecisionContextChangedError(snapshot.token);
    }
    emitDiagnostic(onDiagnostic, {
      type: 'decision.failed',
      stepIndex,
      reason: isAbortError(error) ? 'cancelled' : 'model-request-failed',
    });
    throw error;
  }
  emitDiagnostic(onDiagnostic, {
    type: 'decision.completed',
    stepIndex,
    ...(output.finishReason ? { finishReason: diagnosticFinishReason(output.finishReason) } : {}),
    toolCallCount: output.toolCalls.length,
    hasText: Boolean(output.text?.trim()),
  });
  if (output.toolCalls.length > MAX_CALLS_PER_STEP) {
    throw new Error(`Assistant returned more than ${MAX_CALLS_PER_STEP} capability calls`);
  }
  if (output.toolCalls.length > 0) onActivity?.('executing', stepIndex);
  const results: AssistantCapabilityResult[] = [];
  const replayableCalls = new Map<string, AssistantCapabilityResult>();
  let attemptedCallCount = 0;
  let appliedEffectCount = 0;
  for (const call of output.toolCalls) {
    const callKey = capabilityCallKey(snapshot.token, call.code, call.input);
    const settled = settledCalls.get(callKey);
    if (settled) {
      results.push({ ...settled, callId: call.id });
      replayableCalls.set(callKey, settled);
      emitDiagnostic(onDiagnostic, {
        type: 'capability.completed',
        stepIndex,
        capabilityCode: diagnosticCapabilityCode(call.code),
        outcome: 'replayed',
        pageEffectApplied: false,
      });
      continue;
    }
    attemptedCallCount += 1;
    try {
      const invocation = await registry.invoke(call, snapshot.token, signal);
      const result: AssistantCapabilityResult = {
        callId: call.id,
        capabilityCode: call.code,
        input: call.input as Record<string, unknown>,
        execution: invocation.contextChanged ? 'effect-applied' : 'read',
        output: invocation.value,
        ...(invocation.presentation ? { presentation: invocation.presentation } : {}),
      };
      results.push(result);
      replayableCalls.set(callKey, result);
      if (invocation.contextChanged) {
        appliedEffectCount += 1;
        emitDiagnostic(onDiagnostic, {
          type: 'capability.completed',
          stepIndex,
          capabilityCode: diagnosticCapabilityCode(call.code),
          outcome: 'succeeded',
          pageEffectApplied: true,
        });
        const continuationToken = registry.snapshot()?.token;
        if (continuationToken) {
          replayableCalls.set(capabilityCallKey(continuationToken, call.code, call.input), result);
        }
        return {
          output,
          results,
          contextChanged: true,
          attemptedCallCount,
          appliedEffectCount,
          continuationToken,
          replayableCalls,
        };
      }
      emitDiagnostic(onDiagnostic, {
        type: 'capability.completed',
        stepIndex,
        capabilityCode: diagnosticCapabilityCode(call.code),
        outcome: 'succeeded',
        pageEffectApplied: false,
      });
    } catch (error) {
      if (error instanceof AssistantEffectInterruptedError) {
        results.push({
          callId: call.id,
          capabilityCode: call.code,
          input: call.input as Record<string, unknown>,
          execution: error.execution,
        });
        const interrupted = {
          output,
          results,
          contextChanged: true,
          appliedEffectCount: error.execution === 'effect-applied' ? 1 : 0,
        };
        throw new AssistantConversationInterruptedError(
          [interrupted],
          error.cause,
          isAbortError(error.cause)
            ? 'cancelled'
            : error.cause instanceof StaleAssistantInvocationError
              ? 'context-changed'
              : 'execution-interrupted',
        );
      }
      if (error instanceof StaleAssistantInvocationError || isAbortError(error)) {
        if (appliedEffectCount === 0 && !signal?.aborted) {
          throw new AssistantDecisionContextChangedError(snapshot.token);
        }
        throw error;
      }
      results.push({
        callId: call.id,
        capabilityCode: call.code,
        input: call.input as Record<string, unknown>,
        execution: 'not-applied',
        error: {
          code: error instanceof AssistantCapabilityUsageError ? error.code : 'CAPABILITY_FAILED',
          message:
            error instanceof AssistantCapabilityUsageError
              ? error.message.slice(0, 500)
              : 'Capability execution failed',
        },
      });
      // Repeating a rejected call in the same snapshot cannot repair it.
      // Changed input or an explicit new user turn may try again. Unknown effects terminate above.
      replayableCalls.set(callKey, results.at(-1)!);
      emitDiagnostic(onDiagnostic, {
        type: 'capability.completed',
        stepIndex,
        capabilityCode: diagnosticCapabilityCode(call.code),
        outcome: 'failed',
        pageEffectApplied: false,
      });
    }
    if (!sameAssistantInvocationToken(snapshot.token, registry.snapshot()?.token)) {
      return {
        output,
        results,
        contextChanged: true,
        attemptedCallCount,
        appliedEffectCount,
        replayableCalls,
      };
    }
  }
  return {
    output,
    results,
    contextChanged: false,
    attemptedCallCount,
    appliedEffectCount,
    replayableCalls,
  };
}

function emitDiagnostic(
  observer: AssistantConversationOptions['onDiagnostic'],
  event: AssistantRuntimeDiagnosticEvent,
) {
  if (!observer) return;
  try {
    void Promise.resolve(observer(event)).catch(() => undefined);
  } catch {
    // Diagnostics must never alter assistant execution.
  }
}

function diagnosticSurface(surface: string): AssistantDiagnosticSurface {
  if (surface === 'workbench' || surface === 'module-page' || surface === 'metadata-governance') {
    return surface;
  }
  return 'other';
}

function diagnosticFinishReason(finishReason: string): AssistantDiagnosticFinishReason {
  const normalized = finishReason.toLowerCase();
  if (
    normalized === 'stop' ||
    normalized === 'tool_calls' ||
    normalized === 'length' ||
    normalized === 'content_filter'
  ) {
    return normalized;
  }
  return 'other';
}

function diagnosticCapabilityCode(code: string) {
  return /^[a-z][a-z0-9_.-]{0,79}$/i.test(code) ? code : 'other';
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

function isSamePageSurfaceContextRefresh(error: unknown, current: AssistantInvocationToken | undefined) {
  if (!(error instanceof AssistantDecisionContextChangedError) || current === undefined) return false;
  const previous = error.token;
  return (
    previous.pageInstanceKey === current.pageInstanceKey &&
    previous.surfaceGeneration === current.surfaceGeneration &&
    previous.fallback === current.fallback &&
    previous.interactionRevision !== undefined &&
    previous.interactionRevision === current.interactionRevision &&
    previous.contextRevision !== current.contextRevision
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
