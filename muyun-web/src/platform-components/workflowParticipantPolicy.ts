/** Structural participant facts shared by configuration and publication. */
export interface WorkflowParticipantRule {
  type: string;
  ids?: string[];
  fieldName?: string;
  relation?: string;
  depth?: number;
  headOnly?: boolean;
}

const participantTypes = ['USER', 'ROLE', 'DEPT', 'ORG', 'RELATIVE', 'FIELD', 'INITIATOR_SELF'];

export function workflowParticipantRules(text?: string): WorkflowParticipantRule[] {
  if (!text?.trim()) return [];
  if (!text.trimStart().startsWith('{') && !text.trimStart().startsWith('[')) {
    const raw = text.startsWith('user:') ? text.slice(5) : text;
    if (raw.includes(':')) throw new Error('Invalid participant policy');
    return [
      {
        type: 'USER',
        ids: [
          ...new Set(
            raw
              .split(/[,;]/)
              .map((id) => id.trim())
              .filter(Boolean),
          ),
        ],
      },
    ];
  }
  const parsed: unknown = JSON.parse(text);
  const root = parsed as Record<string, unknown> | null;
  const entries = Array.isArray(parsed) ? parsed : Array.isArray(root?.rules) ? root.rules : [parsed];
  return entries.map((entry: unknown) => {
    if (typeof entry === 'string') return { type: 'USER', ids: entry ? [entry] : [] };
    if (!entry || typeof entry !== 'object' || Array.isArray(entry))
      throw new Error('Invalid participant rule');
    const rule = entry as Record<string, unknown>;
    const declaredType = typeof rule.type === 'string' ? rule.type.toUpperCase() : 'USER';
    const type =
      declaredType === 'DEPARTMENT' ? 'DEPT' : declaredType === 'ORGANIZATION' ? 'ORG' : declaredType;
    if (!participantTypes.includes(type)) throw new Error('Invalid participant type');
    const ids = ['ids', 'userIds', 'targetIds'].flatMap((key) =>
      Array.isArray(rule[key]) ? (rule[key] as unknown[]) : [],
    );
    ids.push(
      ...['userId', 'targetId', 'id', 'value'].map((key) => rule[key]).filter((id) => typeof id === 'string'),
    );
    return {
      type,
      ids: [...new Set(ids.filter((id): id is string => typeof id === 'string' && Boolean(id.trim())))],
      ...(typeof rule.fieldName === 'string' ? { fieldName: rule.fieldName } : {}),
      ...(typeof rule.relation === 'string' ? { relation: rule.relation } : {}),
      ...(typeof rule.depth === 'number' ? { depth: rule.depth } : {}),
      ...(typeof rule.headOnly === 'boolean' ? { headOnly: rule.headOnly } : {}),
    };
  });
}

export function hasWorkflowParticipants(text?: string): boolean {
  try {
    const rules = workflowParticipantRules(text);
    return (
      rules.length > 0 &&
      rules.every((rule) => {
        if (['USER', 'ROLE', 'DEPT', 'ORG'].includes(rule.type)) return Boolean(rule.ids?.length);
        if (rule.type === 'FIELD') return Boolean(rule.fieldName?.trim());
        if (rule.type === 'RELATIVE') return Boolean(rule.relation?.trim());
        return true;
      })
    );
  } catch {
    return false;
  }
}
