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
  output?: unknown;
  error?: {
    code: string;
    message: string;
  };
}

export interface AssistantTurnInput {
  message: string;
  context: AssistantSurfaceContext;
  capabilities: AssistantCapabilityDescriptor[];
  results?: AssistantCapabilityResult[];
}

export interface AssistantTurnOutput {
  text?: string;
  toolCalls: AssistantCapabilityCall[];
  finishReason?: string;
  requestId?: string;
}
