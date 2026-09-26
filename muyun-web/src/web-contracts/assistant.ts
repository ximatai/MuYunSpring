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

export interface AssistantTurnInput {
  message: string;
  history?: AssistantConversationMessage[];
  context: AssistantSurfaceContext;
  capabilities: AssistantCapabilityDescriptor[];
  results?: AssistantCapabilityResult[];
  selectionResponse?: AssistantSelectionResponse;
}

export interface AssistantTurnOutput {
  text?: string;
  toolCalls: AssistantCapabilityCall[];
  selection?: AssistantSelectionInteraction;
  finishReason?: string;
  requestId?: string;
}

/** Trusted local capability presentation; never interpreted from model prose. */
export interface AssistantResultPresentation {
  title: string;
  lines: string[];
  /** Secondary, fully reviewable details; never used as an execution payload. */
  details?: { title: string; lines: string[] };
}
