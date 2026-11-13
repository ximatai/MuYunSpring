export interface PasswordTrialRule {
  pattern: string;
  message: string;
  enabled?: boolean;
}

export type PasswordTrialStatus = 'passed' | 'failed' | 'unsupported';

/** Evaluates the supplied rule set without persisting it; drafts may contain disabled rules. */
export function evaluatePasswordRules(rules: readonly PasswordTrialRule[], password: string) {
  if (!password) return [];
  return rules.flatMap((rule, index) => {
    if (rule.enabled === false) return [];
    let status: PasswordTrialStatus;
    try {
      status = new RegExp(`^(?:${rule.pattern})$`).test(password) ? 'passed' : 'failed';
    } catch {
      // The server accepts Java regex; unsupported browser syntax must not be presented as failure.
      status = 'unsupported';
    }
    return [{ key: `${index}:${rule.pattern}`, label: rule.message || rule.pattern, status }];
  });
}
