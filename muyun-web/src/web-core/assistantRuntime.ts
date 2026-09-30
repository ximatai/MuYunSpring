import { AppError, platformErrorCodes } from './errors';
import type { AssistantOperationConfirmation } from './assistantConfirmation';
import type {
  AssistantCapabilityResult,
  AssistantExecutionBudget,
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
  type AssistantExecutionPolicy,
  type AssistantSurfaceRegistry,
} from './assistantSurface';

const MAX_CALLS_PER_STEP = 8;

function capabilityFailure(error: unknown) {
  if (error instanceof AssistantCapabilityUsageError)
    return { code: error.code, message: error.message.slice(0, 500) };
  if (error instanceof AppError && (error.status === 403 || error.status === 404))
    return {
      code: 'RESOURCE_UNAVAILABLE',
      message:
        '当前身份或范围无法访问该资源，或资源已不存在。不要重复相同请求或绕过访问限制；请向用户说明阻断原因并核实范围。',
    };
  // Only the public validation contract is suitable for model self-correction.
  // Never forward arbitrary HTTP errors, transport errors, causes or server details.
  if (
    error instanceof AppError &&
    [400, 409, 422].includes(error.status ?? 0) &&
    ([platformErrorCodes.validationFailed, platformErrorCodes.conflictVersion] as string[]).includes(
      error.code,
    )
  )
    return { code: error.code, message: error.message.slice(0, 500) };
  return { code: 'CAPABILITY_FAILED', message: 'Capability execution failed' };
}

/** Observations are decision context, never execution authorization or a cache of invocations. */
interface AssistantReadContext {
  token?: AssistantInvocationToken;
  results: AssistantCapabilityResult[];
}

function withReadContext(
  current: AssistantCapabilityResult[],
  memory: AssistantReadContext | undefined,
  token: AssistantInvocationToken,
): AssistantCapabilityResult[] {
  if (!memory) return current;
  if (!sameAssistantInvocationToken(memory.token, token)) memory.results = [];
  memory.token = token;
  const keys = new Set(current.map((result) => JSON.stringify([result.capabilityCode, result.input])));
  let budget = 12_000;
  const retained: AssistantCapabilityResult[] = [];
  for (const result of [...memory.results].reverse()) {
    const key = JSON.stringify([result.capabilityCode, result.input]);
    const size = JSON.stringify(result).length;
    if (keys.has(key) || size > budget || retained.length >= 16 - current.length) continue;
    keys.add(key);
    budget -= size;
    retained.unshift(result);
  }
  return [...retained, ...current];
}

export interface AssistantRuntimeStepResult {
  output: AssistantTurnOutput;
  confirmations?: AssistantOperationConfirmation[];
  results: AssistantCapabilityResult[];
  contextChanged: boolean;
  /** Successful capability invocations that committed an effect through applyEffect in this step. */
  appliedEffectCount: number;
}

interface InternalAssistantRuntimeStepResult extends AssistantRuntimeStepResult {
  attemptedCallCount: number;
  restoredReadContext?: boolean;
  continuationToken?: AssistantInvocationToken;
  replayableCalls: Map<string, AssistantCapabilityResult>;
}

export interface AssistantConversationOptions {
  executionPolicy?: AssistantExecutionPolicy;
  signal?: AbortSignal;
  maxSteps?: number;
  /** Completed dialogue before the current user message. */
  history?: AssistantConversationMessage[];
  /** Structured answer to a selection shown by the immediately preceding assistant message. */
  selectionResponse?: AssistantSelectionResponse;
  onStep?(step: AssistantRuntimeStepResult): void | Promise<void>;
  /** A validated navigation crossed execution scopes; previous model observations were discarded. */
  onExecutionScopeChange?(): void;
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
      type: 'budget.progress';
      stepIndex: number;
      newObservations: number;
      appliedEffects: number;
      extended: boolean;
    }
  | {
      type: 'summary.completed';
      succeeded: boolean;
      reason?: 'truncated' | 'undeclared-tool' | 'provider-rejected' | 'invalid-summary' | 'request-failed';
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
const HARD_MAX_STEPS = 12;
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
  if (initial?.token.executionScopePending) initial = await waitForFormalSurface(initial.token);
  if (initial?.token.identityScopeKey !== identityScope) throw new StaleAssistantInvocationError();
  let executionScope = initial?.token.executionScopeKey;
  let history = options.history ?? [];
  const maxSteps = options.maxSteps ?? DEFAULT_MAX_STEPS;
  if (!Number.isInteger(maxSteps) || maxSteps < 1 || maxSteps > DEFAULT_MAX_STEPS) {
    throw new Error(`Assistant conversation maxSteps must be between 1 and ${DEFAULT_MAX_STEPS}`);
  }
  const hardLimit = options.maxSteps === undefined ? HARD_MAX_STEPS : maxSteps;
  const observations = new Set<string>();
  let unproductiveSteps = 0;
  let madeProgress = false;
  const steps: AssistantRuntimeStepResult[] = [];
  let results: AssistantCapabilityResult[] = [];
  const completedEffects: AssistantCapabilityResult[] = [];
  const readContext: AssistantReadContext = { results: [] };
  let settledCalls = new Map<string, AssistantCapabilityResult>();
  let expectedReplacementToken: AssistantInvocationToken | undefined;
  let decisionRestarts = 0;
  for (let index = 0; index < hardLimit; index += 1) {
    if ((index >= maxSteps && !madeProgress) || unproductiveSteps >= 2) break;
    let current = registry.snapshot();
    if (current?.token.executionScopePending) current = await waitForFormalSurface(current.token);
    if (
      current?.token.identityScopeKey !== identityScope ||
      current?.token.executionScopeKey !== executionScope
    ) {
      throw new StaleAssistantInvocationError();
    }
    let step: InternalAssistantRuntimeStepResult;
    let streamedText = false;
    try {
      step = await runAssistantStepWithSettledCalls({
        registry,
        message,
        history,
        previousResults: results,
        signal: options.signal,
        settledCalls,
        stepIndex: index,
        selectionResponse: index === 0 ? options.selectionResponse : undefined,
        onActivity: options.onActivity,
        onDiagnostic: options.onDiagnostic,
        onTextDelta: options.onTextDelta
          ? (text) => {
              streamedText = true;
              options.onTextDelta?.(text, index);
            }
          : undefined,
        readContext,
        executionPolicy: options.executionPolicy,
        executionBudget: { phase: 'work', step: index + 1, normalLimit: maxSteps, hardLimit },
      });
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
    const crossedScope =
      step.continuationToken?.executionScopeKey !== undefined &&
      step.continuationToken.executionScopeKey !== executionScope;
    if (crossedScope) {
      const replacement = registry.snapshot()?.token;
      if (
        step.continuationToken?.identityScopeKey !== identityScope ||
        step.continuationToken?.pageInstanceKey === current?.token.pageInstanceKey ||
        !sameAssistantInvocationToken(step.continuationToken, replacement)
      )
        throw new StaleAssistantInvocationError();
      executionScope = step.continuationToken?.executionScopeKey;
      history = [];
      results = [];
      completedEffects.length = 0;
      readContext.results = [];
      readContext.token = undefined;
      observations.clear();
    }
    settledCalls = crossedScope ? new Map() : step.replayableCalls;
    let newObservations = 0;
    for (const result of step.results) {
      if (result.error || result.execution !== 'read') continue;
      const key = JSON.stringify([result.capabilityCode, result.output]);
      if (!observations.has(key)) {
        observations.add(key);
        newObservations += 1;
      }
    }
    madeProgress = newObservations > 0 || step.appliedEffectCount > 0;
    unproductiveSteps = madeProgress ? 0 : unproductiveSteps + 1;
    emitDiagnostic(options.onDiagnostic, {
      type: 'budget.progress',
      stepIndex: index,
      newObservations,
      appliedEffects: step.appliedEffectCount,
      extended: index >= maxSteps,
    });
    const publicStep = toPublicStep(step);
    steps.push(publicStep);
    if (crossedScope) {
      options.onExecutionScopeChange?.();
      continue;
    }
    if (step.confirmations?.length) {
      await options.onStep?.(publicStep);
      return { steps, termination: 'waiting-for-user' };
    }
    if (step.output.toolCalls.length === 0) {
      await options.onStep?.(publicStep);
      emitDiagnostic(options.onDiagnostic, {
        type: 'conversation.completed',
        stepCount: steps.length,
        bounded: false,
      });
      return { steps, termination: step.output.selection ? 'waiting-for-user' : 'stopped' };
    }
    if (step.attemptedCallCount === 0 && !step.restoredReadContext) {
      await options.onStep?.({ ...publicStep, results: [] });
      emitDiagnostic(options.onDiagnostic, {
        type: 'conversation.completed',
        stepCount: steps.length,
        bounded: false,
      });
      results = [...completedEffects.slice(-8), ...step.results];
      await summarize();
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
    const observationToken = registry.snapshot()?.token;
    if (
      step.contextChanged ||
      !observationToken ||
      !sameAssistantInvocationToken(readContext.token, observationToken)
    ) {
      readContext.results = [];
      readContext.token = undefined;
    } else {
      readContext.results = withReadContext(results, readContext, observationToken)
        .filter((result) => result.execution === 'read' && !result.error)
        .slice(-8)
        .map((result, position) => ({ ...result, callId: `observation-${index}-${position}` }));
    }
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
  await summarize();
  return { steps, termination: 'step-limit' };

  async function summarize() {
    const token = registry.snapshot()?.token;
    try {
      if (!token || token.identityScopeKey !== identityScope || token.executionScopeKey !== executionScope)
        throw new StaleAssistantInvocationError();
      const output = await registry.requestTurn(
        {
          message,
          history,
          results: withReadContext(results, readContext, token),
          executionBudget: { phase: 'summary', step: steps.length + 1, normalLimit: maxSteps, hardLimit },
        },
        token,
        options.signal,
        undefined,
        options.executionPolicy,
      );
      // A summary is never an execution step, even if a provider ignores the empty tool catalog.
      if (output.toolCalls.length || output.selection || !output.text?.trim())
        throw new Error('Invalid summary');
      const summary = { output, results: [], contextChanged: false, appliedEffectCount: 0 };
      await options.onStep?.(summary);
      steps.push(summary);
      emitDiagnostic(options.onDiagnostic, { type: 'summary.completed', succeeded: true });
    } catch (error) {
      if (options.signal?.aborted || error instanceof StaleAssistantInvocationError || isAbortError(error)) {
        if (hasAppliedCapabilityEffect(steps))
          throw new AssistantConversationInterruptedError(
            steps,
            error,
            options.signal?.aborted || isAbortError(error) ? 'cancelled' : 'context-changed',
          );
        throw error;
      }
      const message = error instanceof Error ? error.message : '';
      const reason =
        message === '模型响应被截断，请缩短描述后重试'
          ? 'truncated'
          : message === 'AI model requested an undeclared tool' ||
              message === 'assistant model returned an undeclared capability call'
            ? 'undeclared-tool'
            : message.startsWith('AI model request was rejected')
              ? 'provider-rejected'
              : message === 'Invalid summary'
                ? 'invalid-summary'
                : 'request-failed';
      emitDiagnostic(options.onDiagnostic, { type: 'summary.completed', succeeded: false, reason });
    }
  }
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
    await runAssistantStepWithSettledCalls({
      registry,
      message,
      history: [],
      previousResults,
      signal,
      settledCalls: new Map(),
    }),
  );
}

function toPublicStep(step: InternalAssistantRuntimeStepResult): AssistantRuntimeStepResult {
  return {
    output: step.output,
    ...(step.confirmations?.length ? { confirmations: step.confirmations } : {}),
    results: step.results,
    contextChanged: step.contextChanged,
    appliedEffectCount: step.appliedEffectCount,
  };
}

interface AssistantStepRequest {
  registry: AssistantSurfaceRegistry;
  message: string;
  history: AssistantConversationMessage[];
  previousResults: AssistantCapabilityResult[];
  signal?: AbortSignal;
  settledCalls: Map<string, AssistantCapabilityResult>;
  stepIndex?: number;
  selectionResponse?: AssistantSelectionResponse;
  onActivity?: AssistantConversationOptions['onActivity'];
  onDiagnostic?: AssistantConversationOptions['onDiagnostic'];
  onTextDelta?: (text: string) => void;
  readContext?: AssistantReadContext;
  executionPolicy?: AssistantExecutionPolicy;
  executionBudget?: AssistantExecutionBudget;
}

async function runAssistantStepWithSettledCalls({
  registry,
  message,
  history,
  previousResults,
  signal,
  settledCalls,
  stepIndex = 0,
  selectionResponse,
  onActivity,
  onDiagnostic,
  onTextDelta,
  readContext,
  executionPolicy,
  executionBudget,
}: AssistantStepRequest): Promise<InternalAssistantRuntimeStepResult> {
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
  previousResults = withReadContext(previousResults, readContext, snapshot.token);
  onActivity?.('understanding', stepIndex);
  let output: AssistantTurnOutput;
  try {
    output = onTextDelta
      ? await registry.requestTurn(
          {
            message,
            history,
            results: previousResults,
            executionBudget,
            ...(selectionResponse ? { selectionResponse } : {}),
          },
          snapshot.token,
          signal,
          {
            onTextDelta(text) {
              onActivity?.('responding', stepIndex);
              onTextDelta(text);
            },
          },
          executionPolicy,
        )
      : await registry.requestTurn(
          {
            message,
            history,
            results: previousResults,
            executionBudget,
            ...(selectionResponse ? { selectionResponse } : {}),
          },
          snapshot.token,
          signal,
          undefined,
          executionPolicy,
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
  // Keep earlier same-context calls as well as the immediately previous step.
  const replayableCalls = new Map(settledCalls);
  let attemptedCallCount = 0;
  let appliedEffectCount = 0;
  let restoredReadContext = false;
  for (const call of output.toolCalls) {
    const callKey = capabilityCallKey(snapshot.token, call.code, call.input);
    const settled = settledCalls.get(callKey);
    if (settled) {
      results.push({ ...settled, callId: call.id });
      // Evicted evidence may be requested again without repeating its execution.
      // Let the model use it before treating another identical request as a loop.
      if (
        settled.execution === 'read' &&
        !settled.error &&
        !previousResults.some(
          (result) =>
            capabilityCallKey(snapshot.token, result.capabilityCode, result.input) === callKey &&
            JSON.stringify(result.output) === JSON.stringify(settled.output),
        )
      )
        restoredReadContext = true;
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
      const invocation = await registry.invoke(call, snapshot.token, signal, executionPolicy);
      const result: AssistantCapabilityResult = {
        callId: call.id,
        capabilityCode: call.code,
        input: call.input as Record<string, unknown>,
        execution: invocation.contextChanged ? 'effect-applied' : 'read',
        output: invocation.value,
        ...(invocation.presentation ? { presentation: invocation.presentation } : {}),
      };
      results.push(result);
      if (invocation.confirmation) {
        return {
          output,
          results,
          confirmations: [invocation.confirmation],
          contextChanged: false,
          appliedEffectCount,
          attemptedCallCount,
          replayableCalls,
        };
      }
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
        error: capabilityFailure(error),
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
    restoredReadContext,
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
  return JSON.stringify([token, code, canonicalCapabilityInput(input)]);
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
