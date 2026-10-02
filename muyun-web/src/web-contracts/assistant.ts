export interface AssistantCapabilityDescriptor {
  code: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export interface AssistantSurfaceContext {
  surface: string;
  title?: string;
  facts: Record<string, unknown>;
}

export interface AssistantCapabilityCall {
  id: string;
  code: string;
  input: unknown;
}

export interface AssistantCapabilityResult {
  callId: string;
  capabilityCode: string;
  input: Record<string, unknown>;
  /** Execution facts, independent of model completion. */
  execution: 'read' | 'effect-applied' | 'not-applied' | 'unknown';
  presentation?: AssistantResultPresentation;
  output?: unknown;
  error?: {
    code: string;
    message: string;
  };
}

export interface AssistantConversationMessage {
  role: 'user' | 'assistant';
  text: string;
}

export interface AssistantSelectionOption {
  id: string;
  label: string;
}

export interface AssistantSelectionInteraction {
  interactionId: string;
  prompt: string;
  inputPolicy: 'free_text_allowed' | 'selection_required';
  presentation: 'options' | 'confirmation';
  options: AssistantSelectionOption[];
}

export interface AssistantSelectionResponse {
  interactionId: string;
  optionId: string;
  label: string;
}

/** Execution budget is informational; it never grants permission to invoke a capability. */
export interface AssistantExecutionBudget {
  phase: 'work' | 'summary';
  step: number;
  normalLimit: number;
  hardLimit: number;
}

export interface AssistantTurnInput {
  executionBudget?: AssistantExecutionBudget;
  message: string;
  history?: AssistantConversationMessage[];
  context: AssistantSurfaceContext;
  capabilities: AssistantCapabilityDescriptor[];
  results?: AssistantCapabilityResult[];
  selectionResponse?: AssistantSelectionResponse;
}

/** Provider-reported counts only. Missing counts are unknown, never estimated as zero. */
export interface AssistantTokenUsage {
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
}

export interface AssistantTurnOutput {
  usage?: AssistantTokenUsage;
  text?: string;
  toolCalls: AssistantCapabilityCall[];
  selection?: AssistantSelectionInteraction;
  finishReason?: string;
  requestId?: string;
}

export type { OperationPresentation as AssistantResultPresentation } from './operation';
import type { OperationPresentation as AssistantResultPresentation } from './operation';
