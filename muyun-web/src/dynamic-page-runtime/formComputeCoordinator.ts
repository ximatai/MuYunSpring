import { FormulaRuntime, type FormulaRecord } from '../formula/FormulaRuntime';
import type { ResolvedFormComputeRuleDescriptor } from '@muyun/web-contracts';

/**
 * Applies server-issued main-form calculation rules to a draft after one or
 * more user-originated field changes.
 *
 * The coordinator owns propagation, rather than the field component or the
 * FormulaRuntime: fields remain plain inputs and FormulaRuntime remains a
 * pure evaluator. A rule runs at most once in a pass, which makes malformed
 * cyclic descriptor graphs fail closed instead of repeatedly overwriting a
 * user's draft.
 */
export class FormComputeCoordinator {
  private readonly rules: readonly ResolvedFormComputeRuleDescriptor[];

  constructor(
    rules: readonly ResolvedFormComputeRuleDescriptor[] | undefined,
    private readonly runtime = new FormulaRuntime(),
  ) {
    this.rules = (rules ?? []).filter((rule) => rule.writePolicy === 'ALWAYS');
  }

  applyAfterChange<TDraft extends FormulaRecord>(draft: TDraft, changedFields: readonly string[]): TDraft {
    if (this.rules.length === 0 || changedFields.length === 0) return draft;
    if (!this.rules.some((rule) => changedFields.some((field) => rule.triggerFields.includes(field)))) {
      return draft;
    }

    let next: FormulaRecord = { ...draft };
    const pendingFields = [...new Set(changedFields)];
    const executedRules = new Set<string>();

    while (pendingFields.length > 0) {
      const changedField = pendingFields.shift();
      if (!changedField) continue;
      for (const rule of this.rules) {
        if (executedRules.has(rule.code) || !rule.triggerFields.includes(changedField)) continue;
        // A descriptor is signed by the server, but do not let an unexpected
        // payload shape re-run a rule or disturb the rest of the user's draft.
        executedRules.add(rule.code);
        const result = this.runtime.evaluateFormCompute(rule.program, next);
        if (result.changedFields.length === 0) continue;
        next = { ...next, ...result.patch };
        pendingFields.push(...result.changedFields);
      }
    }
    return next as TDraft;
  }
}
