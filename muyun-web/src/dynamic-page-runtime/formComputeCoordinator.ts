import { FormulaRuntime, type FormulaRecord } from '../formula/FormulaRuntime';
import type { ResolvedFormComputeRuleDescriptor } from '@muyun/web-contracts';

/**
 * Applies server-issued main-form calculation rules to a draft after one or
 * more user-originated field changes.
 *
 * The coordinator owns propagation, rather than the field component or the
 * FormulaRuntime: fields remain plain inputs and FormulaRuntime remains a
 * pure evaluator. It validates the descriptor graph before evaluating any
 * rule, then evaluates applicable rules in dependency order.
 */
export class FormComputeCoordinator {
  private readonly rules: readonly ResolvedFormComputeRuleDescriptor[];
  private readonly propagationOrder: readonly ResolvedFormComputeRuleDescriptor[] | undefined;

  constructor(
    rules: readonly ResolvedFormComputeRuleDescriptor[] | undefined,
    private readonly runtime = new FormulaRuntime(),
  ) {
    this.rules = (rules ?? []).filter((rule) => rule.writePolicy === 'ALWAYS');
    this.propagationOrder = resolvePropagationOrder(this.rules);
  }

  applyAfterChange<TDraft extends FormulaRecord>(
    draft: TDraft,
    changedFields: readonly string[],
    childAggregateRows?: FormulaRecord,
  ): TDraft {
    if (this.rules.length === 0 || changedFields.length === 0 || !this.propagationOrder) return draft;
    if (!this.rules.some((rule) => changedFields.some((field) => rule.triggerFields.includes(field)))) {
      return draft;
    }

    return this.evaluate(draft, changedFields, false, childAggregateRows);
  }

  /** Evaluate computed fields once when a new form opens, including constant expressions. */
  applyOnCreate<TDraft extends FormulaRecord>(draft: TDraft, childAggregateRows?: FormulaRecord): TDraft {
    return this.evaluate(draft, [], true, childAggregateRows);
  }

  private evaluate<TDraft extends FormulaRecord>(
    draft: TDraft,
    changedFields: readonly string[],
    allRules: boolean,
    childAggregateRows?: FormulaRecord,
  ): TDraft {
    if (!this.propagationOrder || this.propagationOrder.length === 0) return draft;
    let next: FormulaRecord = { ...draft };
    const propagatedFields = new Set(changedFields);

    for (const rule of this.propagationOrder) {
      if (!allRules && !rule.triggerFields.some((field) => propagatedFields.has(field))) continue;
      try {
        // Child rows are stored under page relation field names in the save draft. Formula programs
        // retain metadata relation codes, so use a transient overlay solely for evaluation.
        const evaluationContext = childAggregateRows ? { ...next, ...childAggregateRows } : next;
        const result = this.runtime.evaluateFormCompute(
          rule.program,
          evaluationContext,
          rule.targetValueType,
        );
        if (result.changedFields.length === 0) continue;
        if (!isExpectedWrite(result, rule.targetField)) return draft;
        next = { ...next, ...result.patch };
        result.changedFields.forEach((field) => propagatedFields.add(field));
      } catch {
        // Descriptors are server-issued, but unexpected payloads must not
        // partially overwrite a user draft.
        return draft;
      }
    }
    return next as TDraft;
  }
}

function resolvePropagationOrder(
  rules: readonly ResolvedFormComputeRuleDescriptor[],
): readonly ResolvedFormComputeRuleDescriptor[] | undefined {
  const targetRuleIndexes = new Map<string, number>();
  const codes = new Set<string>();

  for (const [index, rule] of rules.entries()) {
    if (!isWellFormedRule(rule) || codes.has(rule.code) || targetRuleIndexes.has(rule.targetField)) {
      return undefined;
    }
    codes.add(rule.code);
    targetRuleIndexes.set(rule.targetField, index);
  }

  const indegrees = rules.map(() => 0);
  const dependents = rules.map(() => new Set<number>());
  for (const [consumerIndex, rule] of rules.entries()) {
    for (const referencedField of referencedValueFields(rule)) {
      const producerIndex = targetRuleIndexes.get(referencedField);
      if (producerIndex === undefined || producerIndex === consumerIndex) {
        if (producerIndex === consumerIndex) return undefined;
        continue;
      }
      if (dependents[producerIndex].has(consumerIndex)) continue;
      dependents[producerIndex].add(consumerIndex);
      indegrees[consumerIndex] += 1;
    }
  }

  const ready = indegrees.flatMap((indegree, index) => (indegree === 0 ? [index] : []));
  const orderedIndexes: number[] = [];
  while (ready.length > 0) {
    const index = ready.shift();
    if (index === undefined) break;
    orderedIndexes.push(index);
    for (const dependentIndex of dependents[index]) {
      indegrees[dependentIndex] -= 1;
      if (indegrees[dependentIndex] === 0) {
        ready.push(dependentIndex);
        ready.sort((left, right) => left - right);
      }
    }
  }

  return orderedIndexes.length === rules.length ? orderedIndexes.map((index) => rules[index]) : undefined;
}

function referencedValueFields(rule: ResolvedFormComputeRuleDescriptor): ReadonlySet<string> {
  const referencedFields = new Set<string>();
  const expression = rule.program.root.arguments[1];
  collectReferencedFields(expression, referencedFields);
  return referencedFields;
}

function collectReferencedFields(node: unknown, referencedFields: Set<string>): void {
  if (!node || typeof node !== 'object') return;
  const formulaNode = node as { kind?: unknown; field?: unknown; arguments?: unknown };
  if (formulaNode.kind === 'FIELD' && typeof formulaNode.field === 'string') {
    referencedFields.add(formulaNode.field);
  }
  if (!Array.isArray(formulaNode.arguments)) return;
  formulaNode.arguments.forEach((argument) => collectReferencedFields(argument, referencedFields));
}

function isWellFormedRule(rule: ResolvedFormComputeRuleDescriptor): boolean {
  if (
    typeof rule.code !== 'string' ||
    rule.code.length === 0 ||
    typeof rule.targetField !== 'string' ||
    rule.targetField.length === 0 ||
    !Array.isArray(rule.triggerFields) ||
    !rule.triggerFields.every((field) => typeof field === 'string' && field.length > 0)
  ) {
    return false;
  }
  const root = rule.program?.root;
  return (
    root?.kind === 'ASSIGN' &&
    root.operator === '=' &&
    root.arguments.length === 2 &&
    root.arguments[0]?.kind === 'FIELD' &&
    root.arguments[0].field === rule.targetField
  );
}

function isExpectedWrite(
  result: ReturnType<FormulaRuntime['evaluateFormCompute']>,
  targetField: string,
): boolean {
  return (
    result.changedFields.length === 1 &&
    result.changedFields[0] === targetField &&
    Object.hasOwn(result.patch, targetField)
  );
}
