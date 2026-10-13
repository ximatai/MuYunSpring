/** Structural participant facts shared by configuration and publication. */
export interface WorkflowParticipantRule {
  type: string;
  ids?: string[];
  fieldName?: string;
  relation?: string;
  depth?: number;
  headOnly?: boolean;
}

export function workflowParticipantRules(text?: string): WorkflowParticipantRule[] {
  if (!text?.trim()) return [];
  if (text.startsWith('user:')) return [{ type: 'USER', ids: [text.slice(5)] }];
  const parsed: unknown = JSON.parse(text);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
    throw new Error('Invalid participant policy');
  const value = parsed as Record<string, unknown>;
  const rules =
    value.rules ??
    (value.type || value.ids || value.userIds
      ? [{ ...value, type: value.type ?? 'USER', ids: value.ids ?? value.userIds ?? [] }]
      : []);
  if (
    !Array.isArray(rules) ||
    rules.some((rule) => !rule || typeof rule !== 'object' || typeof rule.type !== 'string')
  ) {
    throw new Error('Invalid participant rules');
  }
  return rules as WorkflowParticipantRule[];
}

export function hasWorkflowParticipants(text?: string): boolean {
  try {
    return workflowParticipantRules(text).length > 0;
  } catch {
    return false;
  }
}
