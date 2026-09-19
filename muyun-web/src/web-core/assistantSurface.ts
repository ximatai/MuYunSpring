import type {
  AssistantCapabilityCall,
  AssistantCapabilityDescriptor,
  AssistantSurfaceContext,
  AssistantTurnInput,
  AssistantTurnOutput,
} from '@muyun/web-contracts';
import { inject, provide, type InjectionKey } from 'vue';
import type { AssistantTurnProgress } from './assistantTurnClient';

export interface AssistantCapability<TInput = unknown, TOutput = unknown> {
  descriptor: AssistantCapabilityDescriptor;
  parseInput(input: unknown): TInput;
  execute(input: TInput, context: AssistantCapabilityExecutionContext): Promise<TOutput>;
}

export function emptyAssistantCapabilityInputSchema(): Record<string, unknown> {
  return { type: 'object', additionalProperties: false, properties: {} };
}

export function parseEmptyAssistantCapabilityInput(input: unknown): Record<string, never> {
  if (input === null || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).length > 0) {
    throw new Error('Assistant capability input must be an empty object');
  }
  return {};
}

export interface AssistantCapabilityExecutionContext {
  signal: AbortSignal;
  /** Explicit caller cancellation; unlike signal, it is not aborted by an expected Surface replacement. */
  cancellationSignal?: AbortSignal;
  isCurrent(): boolean;
  /** Commit invocation-local guarded state without claiming a user-visible page effect. */
  commitInternalState<T>(commit: () => T): T;
  /** Apply a user-visible page effect. The runtime reports it separately from reads and internal state. */
  applyEffect<T>(effect: () => T, settle?: () => Promise<void>): T;
}

export interface AssistantSurface {
  describe(): AssistantSurfaceContext;
  capabilities(): AssistantCapability[];
  requestTurn(
    input: AssistantTurnInput,
    signal: AbortSignal,
    progress?: AssistantTurnProgress,
  ): Promise<AssistantTurnOutput>;
}

export interface AssistantSurfaceRegistration {
  pageInstanceKey: string;
  fallback?: boolean;
  contextRevision(): string;
  /** User-controlled page state. Stable values let the runtime distinguish background refreshes. */
  interactionRevision?(): string;
  surface: AssistantSurface;
}

interface RegisteredAssistantSurface extends AssistantSurfaceRegistration {
  surfaceGeneration: number;
}

export interface AssistantInvocationToken {
  pageInstanceKey: string;
  surfaceGeneration: number;
  contextRevision: string;
  interactionRevision?: string;
  fallback: boolean;
}

export interface AssistantSurfaceSnapshot {
  token: AssistantInvocationToken;
  context: AssistantSurfaceContext;
  capabilities: AssistantCapabilityDescriptor[];
}

export interface AssistantSurfaceRegistry {
  register(registration: AssistantSurfaceRegistration): () => void;
  activate(pageInstanceKey: string | undefined): void;
  snapshot(): AssistantSurfaceSnapshot | undefined;
  requestTurn(
    input: Omit<AssistantTurnInput, 'context' | 'capabilities'>,
    token: AssistantInvocationToken,
    signal?: AbortSignal,
    progress?: AssistantTurnProgress,
  ): Promise<AssistantTurnOutput>;
  invoke(
    call: AssistantCapabilityCall,
    token: AssistantInvocationToken,
    signal?: AbortSignal,
  ): Promise<{ value: unknown; contextChanged: boolean }>;
}

export interface AssistantSurfaceHost {
  registry: AssistantSurfaceRegistry;
  activePageInstanceKey(): string | undefined;
  capabilities?(): AssistantCapability[];
}

const assistantSurfaceHostKey: InjectionKey<AssistantSurfaceHost> = Symbol('assistant-surface-host');

export function provideAssistantSurfaceHost(host: AssistantSurfaceHost) {
  provide(assistantSurfaceHostKey, host);
}

export function useAssistantSurfaceHost() {
  return inject(assistantSurfaceHostKey, undefined);
}

export class StaleAssistantInvocationError extends Error {
  constructor() {
    super('Assistant invocation no longer matches the active page context');
    this.name = 'StaleAssistantInvocationError';
  }
}

export function createAssistantSurfaceRegistry(): AssistantSurfaceRegistry {
  const registrations = new Map<string, RegisteredAssistantSurface[]>();
  const pending = new Set<AbortController>();
  let activePageInstanceKey: string | undefined;
  let nextSurfaceGeneration = 0;

  function cancelPending() {
    for (const controller of pending) controller.abort();
    pending.clear();
  }

  function register(registration: AssistantSurfaceRegistration) {
    const registered = { ...registration, surfaceGeneration: ++nextSurfaceGeneration };
    const stack = registrations.get(registration.pageInstanceKey) ?? [];
    if (registration.fallback) stack.unshift(registered);
    else stack.push(registered);
    registrations.set(registration.pageInstanceKey, stack);
    if (activePageInstanceKey === registration.pageInstanceKey) cancelPending();
    return () => {
      const current = registrations.get(registration.pageInstanceKey);
      if (!current?.includes(registered)) return;
      const remaining = current.filter((candidate) => candidate !== registered);
      if (remaining.length > 0) registrations.set(registration.pageInstanceKey, remaining);
      else registrations.delete(registration.pageInstanceKey);
      if (activePageInstanceKey === registration.pageInstanceKey) cancelPending();
    };
  }

  function activate(pageInstanceKey: string | undefined) {
    if (activePageInstanceKey === pageInstanceKey) return;
    activePageInstanceKey = pageInstanceKey;
    cancelPending();
  }

  function active() {
    const stack = activePageInstanceKey ? registrations.get(activePageInstanceKey) : undefined;
    return stack?.at(-1);
  }

  function tokenOf(registration: RegisteredAssistantSurface): AssistantInvocationToken {
    return {
      pageInstanceKey: registration.pageInstanceKey,
      surfaceGeneration: registration.surfaceGeneration,
      contextRevision: registration.contextRevision(),
      interactionRevision: registration.interactionRevision?.(),
      fallback: registration.fallback === true,
    };
  }

  function snapshot(): AssistantSurfaceSnapshot | undefined {
    const registration = active();
    if (!registration) return undefined;
    return {
      token: tokenOf(registration),
      context: registration.surface.describe(),
      capabilities: registration.surface.capabilities().map(({ descriptor }) => descriptor),
    };
  }

  function requireCurrent(token: AssistantInvocationToken) {
    const registration = active();
    if (!registration || !sameToken(token, tokenOf(registration))) {
      throw new StaleAssistantInvocationError();
    }
    return registration;
  }

  async function controlled<T>(
    token: AssistantInvocationToken,
    externalSignal: AbortSignal | undefined,
    requireCurrentAfter: boolean,
    operation: (registration: RegisteredAssistantSurface, signal: AbortSignal) => Promise<T>,
  ) {
    const registration = requireCurrent(token);
    const controller = new AbortController();
    const abort = () => controller.abort();
    externalSignal?.addEventListener('abort', abort, { once: true });
    if (externalSignal?.aborted) controller.abort();
    pending.add(controller);
    try {
      const result = await operation(registration, controller.signal);
      if (requireCurrentAfter) requireCurrent(token);
      if (controller.signal.aborted) {
        throw new DOMException('Assistant invocation was cancelled', 'AbortError');
      }
      return result;
    } finally {
      pending.delete(controller);
      externalSignal?.removeEventListener('abort', abort);
    }
  }

  return {
    register,
    activate,
    snapshot,
    requestTurn(input, token, signal, progress) {
      return controlled(token, signal, true, (registration, controlledSignal) => {
        const current = requireCurrent(token);
        const request = {
          ...input,
          context: current.surface.describe(),
          capabilities: current.surface.capabilities().map(({ descriptor }) => descriptor),
        };
        return progress
          ? registration.surface.requestTurn(request, controlledSignal, progress)
          : registration.surface.requestTurn(request, controlledSignal);
      });
    },
    async invoke(call, token, signal) {
      const registration = requireCurrent(token);
      const controller = new AbortController();
      const cancellationController = new AbortController();
      let explicitlyCancelled = signal?.aborted === true;
      const abort = () => {
        explicitlyCancelled = true;
        controller.abort();
        cancellationController.abort();
      };
      signal?.addEventListener('abort', abort, { once: true });
      if (signal?.aborted) abort();
      pending.add(controller);
      return (async () => {
        const capability = registration.surface
          .capabilities()
          .find(({ descriptor }) => descriptor.code === call.code);
        if (!capability) throw new Error(`Assistant capability is unavailable: ${call.code}`);
        const input = capability.parseInput(call.input);
        requireCurrent(token);
        let postEffectToken: AssistantInvocationToken | undefined;
        let postEffectSettlement: Promise<void> | undefined;
        let effectApplied = false;
        const value = await capability.execute(input, {
          signal: controller.signal,
          cancellationSignal: cancellationController.signal,
          isCurrent: () => {
            try {
              requireCurrent(token);
              return true;
            } catch {
              return false;
            }
          },
          commitInternalState(commit) {
            if (controller.signal.aborted) {
              throw new DOMException('Assistant invocation was cancelled', 'AbortError');
            }
            requireCurrent(token);
            return commit();
          },
          applyEffect(effect, settle) {
            if (controller.signal.aborted) {
              throw new DOMException('Assistant invocation was cancelled', 'AbortError');
            }
            requireCurrent(token);
            const result = effect();
            effectApplied = true;
            const capturePostEffectToken = () => {
              const current = active();
              postEffectToken = current ? tokenOf(current) : undefined;
            };
            if (settle) postEffectSettlement = settle().then(capturePostEffectToken);
            else capturePostEffectToken();
            return result;
          },
        });
        await postEffectSettlement;
        const current = active();
        const currentToken = current ? tokenOf(current) : undefined;
        if (explicitlyCancelled) {
          throw new DOMException('Assistant invocation was cancelled', 'AbortError');
        }
        if (effectApplied) {
          if (!sameOptionalToken(postEffectToken, currentToken)) {
            throw new StaleAssistantInvocationError();
          }
          return { value, contextChanged: true };
        }
        if (controller.signal.aborted) {
          throw new DOMException('Assistant invocation was cancelled', 'AbortError');
        }
        if (!currentToken || !sameToken(token, currentToken)) throw new StaleAssistantInvocationError();
        return { value, contextChanged: false };
      })().finally(() => {
        pending.delete(controller);
        signal?.removeEventListener('abort', abort);
      });
    },
  };
}

function sameToken(left: AssistantInvocationToken, right: AssistantInvocationToken) {
  return (
    left.pageInstanceKey === right.pageInstanceKey &&
    left.surfaceGeneration === right.surfaceGeneration &&
    left.contextRevision === right.contextRevision &&
    left.interactionRevision === right.interactionRevision &&
    left.fallback === right.fallback
  );
}

function sameOptionalToken(
  left: AssistantInvocationToken | undefined,
  right: AssistantInvocationToken | undefined,
) {
  if (!left || !right) return left === right;
  return sameToken(left, right);
}
