import type { FormulaRecord } from '../formula/FormulaRuntime';
import type { ResolvedFormComputeRuleDescriptor, ResolvedModuleUiDescriptor } from '@muyun/web-contracts';

/**
 * Resolves the page draft's embedded child arrays into the relation-code keys used by a
 * server-issued FormulaProgram. The returned object is evaluation-only and must never become
 * part of the persistence payload.
 */
export function childAggregateRows(
  draft: FormulaRecord,
  descriptor: ResolvedModuleUiDescriptor | undefined,
  unavailableRelationCodes: ReadonlySet<string> = new Set(),
): FormulaRecord | undefined {
  const rows: FormulaRecord = {};
  for (const relation of descriptor?.detailRelations ?? []) {
    if (unavailableRelationCodes.has(relation.parentBinding)) continue;
    if (!relation.embeddedField || !Array.isArray(draft[relation.embeddedField])) continue;
    rows[relation.parentBinding] = draft[relation.embeddedField];
  }
  return Object.keys(rows).length === 0 ? undefined : rows;
}

/** Triggers only aggregate rules that read the child relation whose draft has changed. */
export function aggregateChildTriggers(
  relationField: string,
  descriptor: ResolvedModuleUiDescriptor | undefined,
  rules: readonly ResolvedFormComputeRuleDescriptor[] | undefined,
): string[] {
  const tableKeys = new Set(
    (descriptor?.detailRelations ?? [])
      .filter((relation) => relation.embeddedField === relationField)
      .map((relation) => relation.parentBinding),
  );
  if (tableKeys.size === 0) return [];
  return [
    ...new Set(
      (rules ?? []).flatMap((rule) =>
        rule.triggerFields.filter((field) =>
          [...tableKeys].some((tableKey) => field.startsWith(`${tableKey}.`)),
        ),
      ),
    ),
  ];
}

export function aggregateChildRelationCodes(
  relationField: string,
  descriptor: ResolvedModuleUiDescriptor | undefined,
): string[] {
  return [
    ...new Set(
      (descriptor?.detailRelations ?? [])
        .filter((relation) => relation.embeddedField === relationField)
        .map((relation) => relation.parentBinding),
    ),
  ];
}
