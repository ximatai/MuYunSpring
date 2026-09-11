import { FormulaRuntime, type FormulaRecord } from '../formula/FormulaRuntime';
import type { ResolvedFormValidationRuleDescriptor } from '@muyun/web-contracts';

export type FormValidationFailure = Readonly<{
  code: string;
  message: string;
  targetField?: string;
}>;

/**
 * Executes only descriptors that have already passed server projection. Invalid descriptor shapes
 * fail open, preserving the server mutation boundary as the final authority.
 */
export class FormValidationCoordinator {
  private readonly rules: readonly ResolvedFormValidationRuleDescriptor[];

  constructor(
    rules: readonly ResolvedFormValidationRuleDescriptor[] | undefined,
    private readonly runtime = new FormulaRuntime(),
  ) {
    this.rules = rules ?? [];
  }

  validate(draft: FormulaRecord): FormValidationFailure | undefined {
    for (const rule of this.rules) {
      if (!isWellFormedRule(rule)) continue;
      const passed = this.runtime.evaluateFormValidation(rule.program, draft);
      if (passed === false) {
        return {
          code: rule.code,
          message: rule.message,
          ...(rule.targetField ? { targetField: rule.targetField } : {}),
        };
      }
    }
    return undefined;
  }
}

function isWellFormedRule(rule: ResolvedFormValidationRuleDescriptor): boolean {
  return (
    typeof rule.code === 'string' &&
    rule.code.length > 0 &&
    typeof rule.message === 'string' &&
    rule.message.length > 0 &&
    Array.isArray(rule.inputFields) &&
    rule.inputFields.every((field) => /^[A-Za-z][A-Za-z0-9_]*$/.test(field)) &&
    (rule.targetField == null || /^[A-Za-z][A-Za-z0-9_]*$/.test(rule.targetField)) &&
    Array.isArray(rule.program?.referencedFields) &&
    rule.program.referencedFields.length === rule.inputFields.length &&
    rule.program.referencedFields.every((field) => rule.inputFields.includes(field))
  );
}
