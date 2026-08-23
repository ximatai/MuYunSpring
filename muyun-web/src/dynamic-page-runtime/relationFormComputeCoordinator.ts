import { FormulaRuntime, type FormulaRecord } from '../formula/FormulaRuntime';
import type { ResolvedDetailRelationFormComputeRuleDescriptor } from '@muyun/web-contracts';

/** Propagates server-compiled calculations after a user changes one embedded child row. */
export class RelationFormComputeCoordinator {
  constructor(
    private readonly rules: readonly ResolvedDetailRelationFormComputeRuleDescriptor[] | undefined,
    private readonly runtime = new FormulaRuntime(),
  ) {}

  applyAfterChange<TRow extends FormulaRecord>(
    rows: readonly TRow[],
    changedRowKey: string,
    keyOf: (row: TRow) => string,
    changedField: string,
  ): TRow[] {
    const applicable = (this.rules ?? []).filter((rule) => rule.triggerFields.includes(changedField));
    if (applicable.length === 0) return [...rows];
    let next = [...rows];
    const changed = next.find((row) => keyOf(row) === changedRowKey);
    if (!changed) return next;
    for (const rule of applicable) {
      const result = this.runtime.evaluateRelationFormCompute(rule.program, changed, rule.targetField);
      if (result.changedFields.length === 0) continue;
      next = next.map((row) => (keyOf(row) === changedRowKey ? row : ({ ...row, ...result.patch } as TRow)));
    }
    return next;
  }
}
